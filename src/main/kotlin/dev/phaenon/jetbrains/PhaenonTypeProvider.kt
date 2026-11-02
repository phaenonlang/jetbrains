package dev.phaenon.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MemberReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeAnalyserVisitor
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4

/**
 * Gives Phaenon declarations the types their erased syntax spelled, string receivers the `Str`
 * methods, and method chains their element types.
 *
 * PhpStorm asks every registered type provider about every typed element while it computes
 * types, during indexing included, and stores the answer with the element's stub. Four kinds of
 * element get an answer here:
 *
 * - A parameter or property whose declared type was erased by [PhaenonLexer]: the type is rebuilt
 *   from the erased text by [PhaenonTypeText] (`list<User>` → `User[]|ListMethods<User>`).
 * - A function or method whose return type was erased, likewise.
 * - The receiver of a `->` access whose type is `string`, or a class implementing `\Stringable`
 *   that lacks the member: it gets `\Phaenon\Internal\StrMethods`, so `$name->upper()` resolves.
 *   A `class<T>` is a string to the language too (`$class->length()` compiles to
 *   `Str::length($class)`), so it gets them as well; a `::` access never does: on a class-string
 *   it names a member of the class.
 *   `list`, `map` and `bytes` need no such step: their pseudo-class travels with the declared
 *   type. Strings cannot carry it everywhere — every PHP string would turn into an object — so
 *   they get it only where a member is accessed on them.
 * - A call of a pseudo-class method that returns a collection: PhpStorm substitutes the class's
 *   template in `@return list<T>` but not in `ListMethods<T>`, so `$users->filter(...)` would
 *   lose its element type for the next link of the chain. The result is rebuilt from the
 *   receiver's type arguments and, for `map`, `keyBy` and `groupBy`, from the closure's return
 *   type.
 *
 * - A partial application, PHP 8.6's `f(?, 1)`, `$o->m(b: ?)`, `C::m(1, ...)`: the closure it
 *   creates (design/php-versions.md §5.2), its parameters the ones the placeholders stand for —
 *   positional ones first, then named ones in the order written, then what `...` leaves — and its
 *   return type the callee's. PhpStorm, which parses the call as a call ([PhaenonLexer] hands it
 *   `?` as a constant), adds the callee's return type itself; the extension point cannot take a
 *   type away, so `$g = f(?, 1)` is the callee's return type and the closure, and a call of `$g`
 *   is checked against the closure's signature.
 * - A pipe whose stage is a partial application, `$x |> $o->m(1, ?)`: the call it becomes. PhpStorm
 *   types a pipe by the function its right side names, which for a method call is no function.
 * - A `concurrently` block used as a value and a `spawn`, which reach the parser as the closures
 *   they compile to ([PhaenonBlocks]): the block is what its body returns, the spawn an
 *   `Amp\Future` of it. PhpStorm keeps its own `\Closure` beside it, as for a partial application.
 *
 * A receiver's or closure's type may be a signature that only an index lookup can decode (a
 * property of another class, say). Those cases are deferred the way the extension point intends:
 * [getType] returns a signature under this provider's key and [complete] resolves it when the
 * indices are available. A partial application always is: its callee is found through the index.
 */
class PhaenonTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = KEY

    override fun getType(element: PsiElement): PhpType? {
        if (!PhaenonErasedTypes.isPhaenon(element)) return null
        return when (element) {
            is Parameter, is Field -> declared(element as PhpNamedElement, PhaenonErasedTypes.beforeName(element))
            is Function -> declared(element, PhaenonErasedTypes.returnType(element))
            is MethodReference -> partial(element) ?: chain(element)
            is FunctionReference -> partial(element) ?: receiver(element)
            is BinaryExpression -> pipe(element) ?: receiver(element)
            is Variable -> merge(blockValue(element), receiver(element))
            else -> receiver(element)
        }
    }

    private fun merge(a: PhpType?, b: PhpType?): PhpType? = when {
        a == null -> b
        b == null -> a
        else -> PhpType().add(a).add(b)
    }

    // ---------------------------------------------------------------- concurrently and spawn

    /**
     * A variable that holds the value of a `concurrently` block or a `spawn` handle. PhpStorm types
     * the closure such an expression reaches it as without asking the type providers, so the type
     * goes to the variables it is assigned to — `$x = concurrently {…}` what the body returns,
     * `$f = spawn …` an `Amp\Future` of the task's value, `$tasks[$k] = spawn …` an array of them —
     * at every occurrence in the function, block bodies included, as the compiled `use` clause
     * shares them.
     */
    private fun blockValue(variable: Variable): PhpType? {
        val name = variable.name ?: return null
        val scope: PsiElement = PhaenonBlocks.scopeOf(variable) ?: variable.containingFile ?: return null
        return PhaenonBlocks.assignedValues(scope)[name]?.let { assigned ->
            val result = PhpType()
            for ((function, kind, element) in assigned) {
                val body = function.type
                when {
                    kind == PhaenonBlocks.Kind.CONCURRENTLY -> result.add(body)
                    !element -> result.add(if (body.isComplete) future(body) else PhpType().add(signature(FUTURE, "", body)))
                    else -> result.add(if (body.isComplete) future(body).pluralise() else PhpType().add(signature(FUTURES, "", body)))
                }
            }
            result.takeIf { !it.isEmpty }
        }
    }

    /** `\Amp\Future<T>`, the handle of a task whose body gives [body]. */
    private fun future(body: PhpType): PhpType {
        val members = body.typesWithParametrisedParts.filter { !it.startsWith("#") && it != "?" }.toMutableSet()
        if (members.remove(PhpType._TRUE) or members.remove(PhpType._FALSE)) members.add(PhpType._BOOL)
        val argument = members.map(::argument).sorted().joinToString("|").ifEmpty { "mixed" }
        return PhpType().add(PhpType.createParametrizedType(FUTURE_CLASS, listOf(argument)))
    }

    // ---------------------------------------------------------------- partial application

    /** The closure a partial application creates, deferred to [complete]: the callee is an index lookup. */
    private fun partial(call: FunctionReference): PhpType? {
        val shape = PhaenonPartialApplication.of(call) ?: return null
        val callee = call.signature.takeIf { it.isNotEmpty() } ?: return null
        return PhpType().add(signature(PARTIAL, shape.encode(), PhpType().add(callee)))
    }

    /** `$x |> $o->m(1, ?)`: the type of the call the stage becomes, the callee's return type. */
    private fun pipe(expression: BinaryExpression): PhpType? {
        if (expression.operation?.text != "|>") return null
        var right = expression.rightOperand
        while (right is ParenthesizedExpression) right = right.argument
        val call = right as? FunctionReference ?: return null
        if (PhaenonPartialApplication.of(call) == null) return null
        return call.signature.takeIf { it.isNotEmpty() }?.let { PhpType().add(it) }
    }

    private fun completePartial(shapeText: String, callee: PhpType, project: Project): PhpType {
        val shape = PhaenonPartialApplication.decode(shapeText) ?: return PhpType.EMPTY
        val index = PhpIndex.getInstance(project)
        val result = PhpType()
        for (signature in callee.types) {
            for (function in index.getBySignature(signature).filterIsInstance<Function>()) {
                val parameters = shape.closureParameters(function.parameters) ?: continue
                val types = parameters.map { parameter ->
                    val declared = parameter.declaredType.takeIf { !it.isEmpty } ?: parameter.type
                    val type = declared.global(project)
                    if (parameter.isVariadic) PhpType().also { t -> type.typesWithParametrisedParts.forEach { t.add(if (PhpType.isPluralType(it)) PhpType.unpluralize(it) else it) } } else type
                }
                result.add(PhaenonTypeText.closureType(types, function.type.global(project)))
            }
        }
        return result
    }

    private fun declared(element: PhpNamedElement, text: String): PhpType? {
        if (text.isEmpty()) return null
        return PhaenonTypeText(element, PhaenonErasedTypes.typeParameterBounds(element)).parse(text)
    }

    // ---------------------------------------------------------------- string receivers

    private fun receiver(element: PsiElement): PhpType? {
        // A field reference as well: while completing `$s->`, there are no parentheses yet.
        val access = element.parent as? MemberReference ?: return null
        if (access.classReference !== element) return null
        // `$class::make()` on a class-string is a static access to the class it names: Str has
        // nothing to offer there, and an extra receiver type would make PhpStorm look for the
        // member on it too ("not found in class-string|StrMethods").
        if (access.isStatic) return null
        val member = access.name ?: return null
        val base = PhpTypeAnalyserVisitor().getTypeFromAST(element)
        if (base.isEmpty || base.types.any { it == PhaenonTypeText.BYTES_METHODS }) return null
        val result = PhpType()
        if (base.types.any { PhpType.isString(it) }) result.add(STR)
        // A `\Stringable` object has the Str methods after its own. Whether the receiver's class is
        // one, and lacks the member, takes the index: decided in complete(). Only an access that
        // names a Str method (or is being completed) is worth that signature; every other member
        // access on every object would otherwise carry one.
        val wanted = member in STR_METHOD_NAMES || member.startsWith(COMPLETION_DUMMY)
        if (wanted && base.types.any { it.startsWith("#") || isClass(it) }) {
            result.add(signature(STRINGABLE, member, base))
        }
        return if (result.isEmpty) null else result
    }

    private fun completeStringable(member: String, receiver: PhpType, project: Project): PhpType {
        if (receiver.types.any { PhpType.isString(it) }) return STR
        val index = PhpIndex.getInstance(project)
        for (fqn in receiver.types) {
            if (!isClass(fqn)) continue
            for (klass in index.getAnyByFQN(fqn)) {
                if (klass.findMethodByName(member) == null && isStringable(klass)) return STR
            }
        }
        return PhpType.EMPTY
    }

    private fun isStringable(klass: PhpClass): Boolean =
        klass.fqn == "\\Stringable" || klass.findMethodByName("__toString") != null || klass.implementedInterfaces.any { it.fqn == "\\Stringable" }

    // ---------------------------------------------------------------- chains

    private fun chain(call: MethodReference): PhpType? {
        val method = call.name ?: return null
        if (method !in CHAIN_METHODS) return null
        val receiver = call.classReference ?: return null
        val receiverType = PhpTypeAnalyserVisitor().getTypeFromAST(receiver)
        if (receiverType.isEmpty) return null
        val closureType = if (method in CLOSURE_METHODS) closureReturnType(call) else null
        if (receiverType.isComplete && closureType?.isComplete != false) {
            return completeChain(method, receiverType, closureType).takeIf { !it.isEmpty }
        }
        return PhpType().add(signature(CHAIN, method, receiverType, closureType))
    }

    /** The return type of the closure passed to `map`, `keyBy`, `groupBy`; null when there is none. */
    private fun closureReturnType(call: MethodReference): PhpType? {
        // A closure argument is an expression node wrapping the function.
        val argument = call.parameters.firstOrNull() ?: return null
        val closure = argument as? Function ?: argument.firstChild as? Function ?: return null
        return (closure as PhpTypedElement).type
    }

    private fun completeChain(method: String, receiver: PhpType, closure: PhpType?): PhpType {
        val result = PhpType()
        for (part in receiver.typesWithParametrisedParts) {
            val pseudoClass = PhpType.removeParametrisedType(part)
            val arguments = PhpType.getParametrizedParts(part)
            when {
                pseudoClass == PhaenonTypeText.LIST_METHODS -> listResult(method, arguments.getOrNull(0), closure)?.let(result::add)
                pseudoClass == PhaenonTypeText.MAP_METHODS -> mapResult(method, arguments.getOrNull(0), arguments.getOrNull(1), closure)?.let(result::add)
                PhpType.isString(pseudoClass) && method in STR_LIST_METHODS -> result.add(list("\\string"))
            }
        }
        return result
    }

    private fun listResult(method: String, element: String?, closure: PhpType?): PhpType? {
        val t = element ?: return null
        return when (method) {
            "filter", "slice", "concat", "reverse", "sort", "sortBy", "unique" -> list(t)
            "map" -> closure?.let { list(joined(it)) }
            "chunk" -> list(PhpType.pluralise(t, 1))
            "flatten" -> if (t.endsWith("[]")) list(t.removeSuffix("[]")) else null
            "keyBy" -> closure?.let { map(joined(it), t) }
            "groupBy" -> closure?.let { map(joined(it), PhpType.pluralise(t, 1)) }
            else -> null
        }
    }

    private fun mapResult(method: String, key: String?, value: String?, closure: PhpType?): PhpType? {
        val k = key ?: return null
        val v = value ?: return null
        return when (method) {
            "filter", "merge", "with", "without" -> map(k, v)
            "map" -> closure?.let { map(k, joined(it)) }
            "keys" -> list(k)
            "values" -> list(v)
            else -> null
        }
    }

    private fun list(element: String): PhpType = PhpType()
        .add(PhpType.pluralise(element, 1))
        .add(PhpType.createParametrizedType(PhaenonTypeText.LIST_METHODS, listOf(argument(element))))

    private fun map(key: String, value: String): PhpType = PhpType()
        .add(PhpType.pluralise(value, 1))
        .add(PhpType.createParametrizedType(PhaenonTypeText.MAP_METHODS, listOf(argument(key), argument(value))))

    /** PhpStorm writes primitive type arguments without the leading backslash (`ListMethods<string>`). */
    private fun argument(type: String): String = if (PhpType.isPrimitiveType(type)) type.trimStart('\\') else type

    /** A type argument is one string; a union closure type is carried as its first alternative. */
    private fun joined(type: PhpType): String = type.typesWithParametrisedParts.filterNot { it.startsWith("#") }.sorted().firstOrNull() ?: "\\mixed"

    // ---------------------------------------------------------------- deferred resolution

    override fun complete(expression: String, project: Project): PhpType? {
        val payload = expression.removePrefix("#$KEY")
        if (payload.isEmpty()) return null
        val kind = payload[0]
        val fields = payload.substring(1).split(SEPARATOR)
        val member = fields.getOrNull(0) ?: return null
        val receiver = decode(fields.getOrNull(1)) ?: return null
        val resolved = if (kind == PARTIAL) receiver else receiver.global(project)
        return when (kind) {
            STRINGABLE -> completeStringable(member, resolved, project)
            CHAIN -> completeChain(member, resolved, decode(fields.getOrNull(2))?.global(project))
            PARTIAL -> completePartial(member, receiver, project)
            FUTURE -> future(resolved)
            FUTURES -> future(resolved).pluralise()
            else -> null
        }
    }

    /** A signature that decodes to nothing ("not a Stringable", "not a collection") is an answer, not a gap. */
    override fun emptyResultIsComplete(): Boolean = true

    override fun getBySignature(expression: String, visited: Set<String>, depth: Int, project: Project): Collection<PhpNamedElement> = emptyList()

    private fun signature(kind: Char, member: String, receiver: PhpType, closure: PhpType? = null): String =
        "#$KEY$kind$member$SEPARATOR${encode(receiver)}" + (closure?.let { "$SEPARATOR${encode(it)}" } ?: "")

    /** A signature is one type string: the `|` of a union would split it. */
    private fun encode(type: PhpType): String = type.typesWithParametrisedParts.joinToString(PIPE.toString())

    private fun decode(text: String?): PhpType? {
        if (text.isNullOrEmpty()) return null
        val type = PhpType()
        text.split(PIPE).forEach { type.add(it) }
        return type
    }

    private fun isClass(type: String): Boolean =
        !type.startsWith("#") && !PhpType.isPrimitiveType(type) && !PhpType.isPluralType(type) && !PhpType.isArray(type)

    private companion object {
        /** Unique among type providers; a letter no other known plugin uses. */
        const val KEY = 'Φ'
        const val STRINGABLE = 'S'
        const val CHAIN = 'C'
        const val PARTIAL = 'P'
        const val FUTURE = 'F'
        const val FUTURES = 'A'
        const val FUTURE_CLASS = "\\Amp\\Future"
        const val SEPARATOR = '¦'
        const val PIPE = '¶'
        val STR: PhpType = PhpType().add(PhaenonTypeText.STR_METHODS)
        const val COMPLETION_DUMMY = "IntellijIdeaRulezzz"

        /** The methods of \Phaenon\Str (stdlib/runtime/Str.phn), as the bundled StrMethods stub declares them. */
        val STR_METHOD_NAMES: Set<String> = setOf(
            "length", "isEmpty", "upper", "lower", "upperFirst", "trim", "trimStart", "trimEnd", "startsWith", "endsWith",
            "contains", "indexOf", "lastIndexOf", "slice", "split", "lines", "replace", "replaceAll", "repeat", "padStart",
            "padEnd", "chars", "graphemes", "graphemeLength", "truncate", "matches", "bytes",
        )

        val CHAIN_METHODS: Set<String> = setOf(
            "filter", "slice", "concat", "reverse", "sort", "sortBy", "unique", "map", "chunk", "flatten", "keyBy", "groupBy",
            "merge", "with", "without", "keys", "values", "split", "lines", "chars", "graphemes",
        )
        val STR_LIST_METHODS: Set<String> = setOf("split", "lines", "chars", "graphemes")
        /** The methods whose result depends on what the closure argument returns. */
        val CLOSURE_METHODS: Set<String> = setOf("map", "keyBy", "groupBy")
    }
}
