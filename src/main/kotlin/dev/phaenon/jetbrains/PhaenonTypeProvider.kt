package dev.phaenon.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4
import java.util.concurrent.ConcurrentHashMap

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
 * - A parameter, property or function whose type names a `type` alias, in whatever file the alias
 *   is declared: the type the alias stands for (design/generics.md §11.6), a generic alias with
 *   its arguments, spelled as the docblock the compiler writes for the declaration and read by
 *   PhpStorm as it reads that docblock. So `$row['id']`, a `foreach` over `$order['lines']` and
 *   `$order['buyer']->name` resolve behind `Row $row` and `find(): ?Order` as they do in the
 *   compiled PHP. Which names are aliases only the index knows, so every member of a type that
 *   names something that may be one is deferred ([complete]). Where it names none, the deferred
 *   type completes to what the member is anyway, the class or the collection of classes, so that
 *   PhpStorm, which builds further types on each member (`new $class`, `$o->m()`), builds on the
 *   deferred one what it builds on its own. The name itself stays a class reference to PhpStorm,
 *   whose own type for it, a class nobody declared, stays beside the alias's: the extension point
 *   adds and never removes.
 * - `$row['id']` where what is indexed has such a deferred type of its own: a parameter typed with
 *   an alias, a variable assigned from one, the value of a `foreach` over one of its keys. PhpStorm
 *   reads a key's type off a property, a function or a method through its own shape index, which
 *   the deferred type above feeds, but off a parameter only from the parameter's docblock, and a
 *   Phaenon parameter has its type in the signature. So the entry is looked up here: the access is
 *   deferred with its key, and [complete] reads the entry in the type the alias stands for.
 * - A partial application up to PhpStorm 2026.2, PHP 8.6's `f(?, 1)`, `$o->m(b: ?)`,
 *   `C::m(1, ...)`: the closure it creates (design/php-versions.md §5.2), its parameters the ones
 *   the placeholders stand for — positional ones first, then named ones in the order written, then
 *   what `...` leaves — and its return type the callee's. PhpStorm, which parses the call as a call
 *   ([PhaenonLexer] hands it `?` as a constant), adds the callee's return type itself; the
 *   extension point cannot take a type away, so `$g = f(?, 1)` is the callee's return type and the
 *   closure, and a call of `$g` is checked against the closure's signature. From 2026.3 on
 *   PhpStorm parses the placeholders itself: the call is its own partial application, no
 *   [FunctionReference], which it types as the closure alone, and this provider leaves it to
 *   PhpStorm.
 * - A pipe whose stage is a partial application, `$x |> $o->m(1, ?)`: the call it becomes. PhpStorm
 *   types a pipe by the function its right side names, which for a method call is no function;
 *   a stage that is its own partial application (2026.3) it types itself.
 *
 * A type that only an index lookup can decode is deferred the way the extension point intends:
 * [getType] returns a signature under this provider's key and [complete] resolves it when the
 * indices are available. A partial application always is: its callee is found through the index.
 */
class PhaenonTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = KEY

    override fun getType(element: PsiElement): PhpType? {
        if (!PhaenonErasedTypes.isPhaenon(element)) return null
        return when (element) {
            is Parameter, is Field ->
                declared(element as PhpNamedElement, PhaenonErasedTypes.beforeName(element)).with(aliased(element, PhaenonErasedTypes.writtenType(element)))
            is Function -> declared(element, PhaenonErasedTypes.returnType(element)).with(aliased(element, PhaenonErasedTypes.writtenReturnType(element)))
            is FunctionReference -> partial(element)
            is ArrayAccessExpression -> keyed(element)
            is BinaryExpression -> pipe(element)
            else -> null
        }
    }

    // ---------------------------------------------------------------- partial application

    /** The closure a partial application creates, deferred to [complete]: the callee is an index lookup. */
    private fun partial(call: FunctionReference): PhpType? {
        val shape = PhaenonPartialApplication.of(call) ?: return null
        val callee = signatures(call) ?: return null
        return PhpType().add(signature(PARTIAL, shape.encode(), callee))
    }

    /** `$x |> $o->m(1, ?)`: the type of the call the stage becomes, the callee's return type. */
    private fun pipe(expression: BinaryExpression): PhpType? {
        if (expression.operation?.text != "|>") return null
        var right = expression.rightOperand
        while (right is ParenthesizedExpression) right = right.argument
        val call = right as? FunctionReference ?: return null
        if (PhaenonPartialApplication.of(call) == null) return null
        return signatures(call)
    }

    /**
     * The signatures of a call's callee, one for each type its receiver may have, each a member of
     * its own: PhpStorm joins them with `|`, and joined they are no signature.
     */
    private fun signatures(call: FunctionReference): PhpType? {
        val type = PhpType()
        call.signature.split('|').filter { it.isNotEmpty() }.forEach { type.add(it) }
        return type.takeIf { !it.isEmpty }
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

    // ---------------------------------------------------------------- type aliases

    /**
     * The deferred type of a declaration whose written type, erased parts and native ones together,
     * names what may be an alias: the type with its names qualified, for [complete] to expand. A
     * declaration whose signature names none may name one in its docblock, `@var list<Order>`.
     */
    private fun aliased(element: PhpNamedElement, written: String?): PhpType? =
        deferred(element, written) ?: deferred(element, PhaenonAliases.documentedType(element))

    /**
     * [text], a type as the signature or a docblock tag spells it, deferred when it names what may
     * be an alias. A docblock type Phaenon's syntax does not read (`User[]`, `array-key`) is left
     * to PhpStorm, which reads all of them.
     */
    private fun deferred(element: PhpNamedElement, text: String?): PhpType? {
        if (text.isNullOrEmpty()) return null
        val type = PhaenonType.parse(text) ?: return null
        val qualified = PhaenonTypeText(element, PhaenonErasedTypes.typeParameterBounds(element)).qualified(type)
        // `null`, `static` and the like are PhpStorm's to type: only the members that name something
        // go. Nor does `class<T>`, a string that names a class and never an alias, which PhpStorm
        // reads off the declaration itself for `new $class` and `$class::m()`.
        val members = (if (qualified is PhaenonType.Union) qualified.members else listOf(qualified))
            .filter { member -> !(member is PhaenonType.Named && !member.qualified && member.simple == "class") }
            .filter { member -> member.names { it.name.startsWith("\\") } }
        if (members.isEmpty()) return null
        return PhpType().add("$ALIASED${escape((members.singleOrNull() ?: PhaenonType.Union(members)).text())}")
    }

    private fun PhpType?.with(other: PhpType?): PhpType? = when {
        this == null -> other
        other == null -> this
        else -> PhpType().add(this).add(other)
    }

    /**
     * What a deferred type is: where it names an alias, the type with every alias replaced, as
     * PhpStorm reads the docblock that spells it; where it names none, the type as this provider
     * gives any Phaenon type, which is what PhpStorm has for it already.
     */
    private fun completeAlias(text: String, project: Project): PhpType? = denoted("$ALIASED$text", project, 0)?.let { phpType(it, project) }

    /**
     * `$row['id']`, `$rows[0]`, `$row[$key]` where the indexed expression's own type holds a
     * deferred type of this provider: the same, one key further. The key is a string or an
     * integer written out, else any key.
     */
    private fun keyed(access: ArrayAccessExpression): PhpType? {
        val base = access.value as? PhpTypedElement ?: return null
        val bases = base.type.types.filter { (ALIASED in it || KEYED_BY in it) && '<' !in it && '|' !in it }
        if (bases.isEmpty()) return null
        val key = when (val index = access.index?.value) {
            is StringLiteralExpression -> shapeKey(index.contents)
            null -> ANY_KEY
            else -> index.text.takeIf { INTEGER.matches(it) }?.let { "i$it" } ?: ANY_KEY
        }
        val type = PhpType()
        for (signature in bases) type.add("$KEYED_BY${escape(key)}$SEPARATOR$signature")
        return type
    }

    /** The type of the entry a keyed access reads, null when what is indexed has no such key or is nothing this provider reads. */
    private fun completeKeyed(payload: String, project: Project): PhpType? = denoted("$KEYED_BY$payload", project, 0)?.let { phpType(it, project) }

    /** A Phaenon type a deferred type denotes, and whether an alias was replaced in it. */
    private class Denoted(val type: PhaenonType, val aliased: Boolean)

    private fun phpType(denoted: Denoted, project: Project): PhpType? =
        if (denoted.aliased) docblockType(project, denoted.type.docblock()) else PhaenonTypeText().type(denoted.type)

    /**
     * The type a deferred type of this provider denotes: the type itself with its aliases
     * replaced, an entry of one, or either as PhpStorm wraps it for the value of a `foreach` over
     * it or after a test that excludes `null` or `false`. Null for anything else, which is
     * PhpStorm's to type.
     */
    private fun denoted(signature: String, project: Project, depth: Int): Denoted? {
        if (depth > MAX_KEYS) return null
        return when {
            signature.startsWith(ALIASED) -> {
                val type = PhaenonType.parse(unescape(signature.substring(ALIASED.length))) ?: return null
                PhaenonAliases.expanded(project, type)?.let { Denoted(it, aliased = true) } ?: Denoted(type, aliased = false)
            }
            signature.startsWith(KEYED_BY) -> {
                val payload = signature.substring(KEYED_BY.length)
                val separator = payload.indexOf(SEPARATOR)
                if (separator < 0) return null
                val base = denoted(payload.substring(separator + 1), project, depth + 1) ?: return null
                entry(base.type, unescape(payload.substring(0, separator)))?.let { Denoted(it, base.aliased) }
            }
            signature.startsWith(FOREACH_VALUE) -> {
                val base = denoted(signature.substring(FOREACH_VALUE.length), project, depth + 1) ?: return null
                entry(base.type, ANY_KEY)?.let { Denoted(it, base.aliased) }
            }
            signature.startsWith(EXCLUDING) && EXCLUDED in signature ->
                denoted(signature.substring(EXCLUDING.length).substringBeforeLast(EXCLUDED), project, depth + 1)
            else -> null
        }
    }

    /** What [type] holds under [key]: a shape's entry, a tuple's element, a list's or a map's value; for any key, each of them. */
    private fun entry(type: PhaenonType, key: String): PhaenonType? = when (type) {
        is PhaenonType.Shape -> if (key == ANY_KEY) union(type.entries.map { it.type }) else type.entries.firstOrNull { shapeKey(it.key.trim('\'', '"')) == key }?.type
        is PhaenonType.Tuple -> if (key == ANY_KEY) union(type.elements) else key.removePrefix("i").toIntOrNull()?.let(type.elements::getOrNull)
        is PhaenonType.Union -> union(type.members.mapNotNull { entry(it, key) })
        is PhaenonType.Named -> type.arguments.lastOrNull().takeIf { !type.qualified && type.simple.lowercase() in COLLECTIONS }
        else -> null
    }

    private fun union(members: List<PhaenonType>): PhaenonType? = when (members.size) {
        0 -> null
        1 -> members[0]
        else -> PhaenonType.Union(members)
    }

    /** A key as PHP stores it: a decimal integer, written bare or quoted, is that integer; anything else the string. */
    private fun shapeKey(text: String): String = if (CANONICAL_INTEGER.matches(text)) "i$text" else "s$text"

    /**
     * The type PhpStorm reads from the docblock type [text], through a function that returns it:
     * a shape is held by PhpStorm in an encoding of its own, which only its own reading of a
     * docblock writes. Kept per text until the project's code changes.
     */
    private fun docblockType(project: Project, text: String): PhpType? {
        val types = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, PhpType>(), PsiModificationTracker.getInstance(project))
        }
        return types.computeIfAbsent(text) {
            val function = PhpPsiElementFactory.createPhpPsiFromText(project, Function::class.java, "/**\n * @return $text\n */\nfunction f() {}")
            PhpType().add(function.type)
        }.takeIf { !it.isEmpty }
    }

    /**
     * A signature is one word to PhpStorm, which reads `|`, `<`, `(` and the like in a type string
     * as its own syntax: everything but letters, digits, `_` and `\` goes in as its code.
     */
    private fun escape(text: String): String = buildString {
        for (c in text) if (c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '\\') append(c) else append(ESCAPE).append("%04x".format(c.code))
    }

    private fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            if (text[i] == ESCAPE && i + 4 < text.length) {
                append(text.substring(i + 1, i + 5).toInt(16).toChar())
                i += 5
            } else {
                append(text[i])
                i++
            }
        }
    }

    // ---------------------------------------------------------------- deferred resolution

    override fun complete(expression: String, project: Project): PhpType? {
        val payload = expression.removePrefix("#$KEY")
        if (payload.isEmpty()) return null
        val kind = payload[0]
        if (kind == ALIAS) return completeAlias(payload.substring(1), project)
        if (kind == KEYED) return completeKeyed(payload.substring(1), project)
        val fields = payload.substring(1).split(SEPARATOR)
        val member = fields.getOrNull(0) ?: return null
        val type = decode(fields.getOrNull(1)) ?: return null
        return when (kind) {
            PARTIAL -> completePartial(member, type, project)
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
        const val ALIAS = 'A'
        const val KEYED = 'K'
        const val ALIASED = "#$KEY$ALIAS"
        const val KEYED_BY = "#$KEY$KEYED"
        /** How many keys deep an access is followed. */
        const val MAX_KEYS = 32
        const val ANY_KEY = "%"
        val INTEGER = Regex("-?\\d+")
        val CANONICAL_INTEGER = Regex("0|-?[1-9]\\d*")
        val COLLECTIONS: Set<String> = setOf("list", "map", "array", "iterable")

        /**
         * How PhpStorm wraps a type it defers: the value of a `foreach` over it, and the type left
         * once a test excluded `null` or `false` (`#-…∆n`). They are PhpStorm's spellings, read
         * here and never written: one this provider does not know leaves the access untyped.
         */
        const val FOREACH_VALUE = "#顶"
        const val EXCLUDING = "#-"
        const val EXCLUDED = '∆'
        /** Introduces an escaped character of an alias signature: a letter, so the signature stays one word. */
        const val ESCAPE = 'ǂ'
        const val SEPARATOR = '¦'
        const val PIPE = '¶'
    }
}
