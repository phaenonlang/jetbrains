package dev.phaenon.jetbrains

import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * Prints a type PhpStorm holds in Phaenon's spelling: the reverse of [PhaenonTypeText].
 *
 * PhpStorm prints every class-string as `string`, so a `.phn` declared `class<T>` reads `string`
 * in its popups. This turns the members back into what the source says:
 *
 * | PhpStorm holds | printed |
 * |---|---|
 * | `\class-string<\App\User>`, `\class-string<\A,\B>`, `\class-string` | `class<User>`, `class<A\|B>`, `class` |
 * | `\Closure` and `\Closure<…>` with a signature | `Closure(A, B): R`, parenthesised in a union |
 * | `T\|null` | `?T` |
 *
 * Everything else prints as PhpStorm prints it, class names short. [render] gives plain text;
 * [Sink] lets the documentation popup style the parts and link the classes.
 */
object PhaenonTypePresentation {

    /** Where the parts of a printed type go: plain text, or the documentation popup's HTML. */
    interface Sink {
        /** A class or interface, [fqn] with its leading backslash. */
        fun klass(fqn: String)

        /** A keyword-like type: `int`, `class`, `null`. */
        fun keyword(name: String)

        /** Punctuation: `<`, `>`, `|`, `, `, `(`, `): `, `?`. */
        fun punctuation(text: String)
    }

    /** Whether [type] holds a member that PhpStorm prints differently from Phaenon: a class-string, a closure signature. */
    fun hasPhaenonMembers(type: PhpType): Boolean = members(type).any { isPhaenonMember(it) }

    /** [type] in Phaenon's spelling, plain text, class names short. */
    fun render(type: PhpType): String {
        val out = StringBuilder()
        print(type, object : Sink {
            override fun klass(fqn: String) {
                out.append(fqn.substringAfterLast('\\'))
            }

            override fun keyword(name: String) {
                out.append(name)
            }

            override fun punctuation(text: String) {
                out.append(text)
            }
        })
        return out.toString()
    }

    /** Prints [type] into [sink]. */
    fun print(type: PhpType, sink: Sink) = printMembers(members(type), sink)

    private fun members(type: PhpType): List<String> =
        type.typesWithParametrisedParts.filter { it.isNotEmpty() && !it.startsWith("?") && !it.startsWith("#") }

    private fun isPhaenonMember(member: String): Boolean {
        val base = PhpType.removeParametrisedType(member)
        return base == PhpType._CLASS_STRING || base == PhpType._CLOSURE && PhaenonTypeText.hasTypeArguments(member)
    }

    private fun printMembers(raw: List<String>, sink: Sink) {
        // PhpStorm's order: by the name without type arguments, case-insensitive.
        val members = LinkedHashSet(raw.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { PhpType.removeParametrisedType(it) }))
        val printed = ArrayList<(Sink) -> Unit>()
        /** The indices in [printed] of closure types, parenthesised in a union: `string|(Closure(int): string)`. */
        val closures = HashSet<Int>()
        var nullable = false

        if (members.any { PhpType.removeParametrisedType(it) == PhpType._CLOSURE && PhaenonTypeText.hasTypeArguments(it) }) {
            members.remove(PhpType._CLOSURE)
        }
        for (member in members) {
            val base = PhpType.removeParametrisedType(member)
            val arguments = PhpType.getParametrizedParts(member)
            when {
                base == PhpType._NULL -> nullable = true
                base == PhpType._CLASS_STRING -> printed.add { s -> classString(arguments, s) }
                base == PhpType._CLOSURE && arguments.isNotEmpty() -> {
                    closures.add(printed.size)
                    printed.add { s -> closure(arguments, s) }
                }
                else -> printed.add { s -> plain(member, s) }
            }
        }
        if (printed.isEmpty()) {
            if (nullable) sink.keyword("null")
            return
        }
        if (nullable && printed.size == 1) sink.punctuation("?")
        val union = printed.size > 1 || nullable
        printed.forEachIndexed { i, part ->
            if (i > 0) sink.punctuation("|")
            val grouped = union && i in closures
            if (grouped) sink.punctuation("(")
            part(sink)
            if (grouped) sink.punctuation(")")
        }
        if (nullable && printed.size > 1) {
            sink.punctuation("|")
            sink.keyword("null")
        }
    }

    private fun classString(arguments: List<String>, sink: Sink) {
        sink.keyword("class")
        if (arguments.isEmpty()) return
        sink.punctuation("<")
        arguments.forEachIndexed { i, argument ->
            if (i > 0) sink.punctuation("|")
            argument(argument, sink)
        }
        sink.punctuation(">")
    }

    /** `\Closure<#ᤓᤓ\A,…,\R>`: PhpStorm's encoding of a signature, the last part the return type. */
    private fun closure(arguments: List<String>, sink: Sink) {
        sink.keyword("Closure")
        sink.punctuation("(")
        arguments.dropLast(1).forEachIndexed { i, part ->
            if (i > 0) sink.punctuation(", ")
            val parameter = PhpExpressionImpl.getTypeFromSerializedClosureParameter(part).second
            if (parameter == null || parameter.isEmpty) sink.keyword("mixed") else printMembers(members(parameter), sink)
        }
        sink.punctuation("): ")
        argument(arguments.last().replace(PhpExpressionImpl.CALLABLE_PARAMS_TYPES_SEPARATOR, "|"), sink)
    }

    /** A type argument: one type, or a union PhpStorm joined into one string. */
    private fun argument(text: String, sink: Sink) {
        if (text.isEmpty()) {
            sink.keyword("mixed")
            return
        }
        val parts = splitTopLevel(text).map { it.trim() }.filter { it.isNotEmpty() }
        val members = parts.map { if (!it.startsWith("\\") && !it.startsWith("#") && PhpType.isPrimitiveType(it)) "\\$it" else it }
        // A template argument is printed by name; elsewhere signatures are left out.
        if (members.all { it.startsWith("#") }) members.forEachIndexed { i, m -> if (i > 0) sink.punctuation("|"); plain(m, sink) }
        else printMembers(members, sink)
    }

    private fun plain(member: String, sink: Sink) {
        if (member.startsWith("#")) {
            // A template PhpStorm read from a docblock, `#ᢐ\T.` for `@template T`: its name.
            sink.keyword(member.substringAfterLast('\\').trimEnd('.'))
            return
        }
        if (PhpType.isPluralType(member)) {
            plain(PhpType.unpluralize(member), sink)
            sink.punctuation("[]")
            return
        }
        val base = PhpType.removeParametrisedType(member)
        if (PhpType.isPrimitiveType(base) || base in PSEUDO_PRIMITIVES) sink.keyword(base.trimStart('\\')) else sink.klass(base)
        val arguments = PhpType.getParametrizedParts(member)
        if (arguments.isNotEmpty() && arguments.any { it.isNotEmpty() }) {
            sink.punctuation("<")
            arguments.forEachIndexed { i, argument ->
                if (i > 0) sink.punctuation(", ")
                argument(argument, sink)
            }
            sink.punctuation(">")
        }
    }

    /** `A|B<C|D>` → `A`, `B<C|D>`: the union's members, a type argument's own union kept whole. */
    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in text.withIndex()) {
            when (c) {
                '<' -> depth++
                '>' -> depth--
                '|' -> if (depth == 0) {
                    parts.add(text.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    /** Types PhpStorm holds that are no class, beside its primitives. */
    private val PSEUDO_PRIMITIVES: Set<String> = setOf("\\resource", "\\number", "\\closed-resource")
}
