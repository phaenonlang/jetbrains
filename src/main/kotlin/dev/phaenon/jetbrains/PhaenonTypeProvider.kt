package dev.phaenon.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4

/**
 * Gives Phaenon declarations the types their erased syntax spelled, and the expressions PhpStorm
 * reads as something else the types they have.
 *
 * PhpStorm asks every registered type provider about every typed element while it computes
 * types, during indexing included, and stores the answer with the element's stub. These elements
 * get an answer here:
 *
 * - A parameter or property whose declared type was erased by [PhaenonLexer]: the type is rebuilt
 *   from the erased text by [PhaenonTypeText] (`list<User>` → `User[]`).
 * - A function or method whose return type was erased, likewise.
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
 * A type that only an index lookup can decode (a callee, a body typed through another class) is
 * deferred the way the extension point intends: [getType] returns a signature under this
 * provider's key and [complete] resolves it when the indices are available. A partial application
 * always is: its callee is found through the index.
 */
class PhaenonTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = KEY

    override fun getType(element: PsiElement): PhpType? {
        if (!PhaenonErasedTypes.isPhaenon(element)) return null
        return when (element) {
            is Parameter, is Field -> declared(element as PhpNamedElement, PhaenonErasedTypes.beforeName(element))
            is Function -> declared(element, PhaenonErasedTypes.returnType(element))
            is FunctionReference -> partial(element)
            is BinaryExpression -> pipe(element)
            is Variable -> blockValue(element)
            else -> null
        }
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

    /** PhpStorm writes primitive type arguments without the leading backslash (`Future<string>`). */
    private fun argument(type: String): String = if (PhpType.isPrimitiveType(type)) type.trimStart('\\') else type

    // ---------------------------------------------------------------- deferred resolution

    override fun complete(expression: String, project: Project): PhpType? {
        val payload = expression.removePrefix("#$KEY")
        if (payload.isEmpty()) return null
        val kind = payload[0]
        val fields = payload.substring(1).split(SEPARATOR)
        val member = fields.getOrNull(0) ?: return null
        val type = decode(fields.getOrNull(1)) ?: return null
        return when (kind) {
            PARTIAL -> completePartial(member, type, project)
            FUTURE -> future(type.global(project))
            FUTURES -> future(type.global(project)).pluralise()
            else -> null
        }
    }

    /** A signature that decodes to nothing (a callee that is no function) is an answer, not a gap. */
    override fun emptyResultIsComplete(): Boolean = true

    override fun getBySignature(expression: String, visited: Set<String>, depth: Int, project: Project): Collection<PhpNamedElement> = emptyList()

    private fun signature(kind: Char, member: String, type: PhpType): String = "#$KEY$kind$member$SEPARATOR${encode(type)}"

    /** A signature is one type string: the `|` of a union would split it. */
    private fun encode(type: PhpType): String = type.typesWithParametrisedParts.joinToString(PIPE.toString())

    private fun decode(text: String?): PhpType? {
        if (text.isNullOrEmpty()) return null
        val type = PhpType()
        text.split(PIPE).forEach { type.add(it) }
        return type
    }

    private companion object {
        /** Unique among type providers; a letter no other known plugin uses. */
        const val KEY = 'Φ'
        const val PARTIAL = 'P'
        const val FUTURE = 'F'
        const val FUTURES = 'A'
        const val FUTURE_CLASS = "\\Amp\\Future"
        const val SEPARATOR = '¦'
        const val PIPE = '¶'
    }
}
