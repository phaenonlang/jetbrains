package dev.phaenon.jetbrains

/**
 * Phaenon type text as a tree, before any name is resolved.
 *
 * The same text is read two ways: [PhaenonTypeText] turns it into the `PhpType` the editor reasons
 * with (`list<User>` → `User[]`), [PhaenonErasure] into the native type the
 * compiler writes for it (`list<User>` → `array`), which is what PHP compares when a method
 * overrides another. The parser accepts the fragments [PhaenonErasedTypes] collects: the erased
 * members of a union around a native one start or end with their separator (`|list<int>`).
 *
 * A shape, `array{id: int, name?: string}`, is read wherever a type is, though it is written in a
 * `type` declaration alone (design/collections.md §6): [alias] reads such a declaration, and
 * [PhaenonAliases] what its name stands for. Comments are blanks to the parser, as to the lexer.
 */
sealed interface PhaenonType {

    /** `Foo`, `\Ns\Foo`, `int`, `T`, `list<T>`, `map<K, V>`, `class<T>`, `bytes`, `Box<User>`. */
    data class Named(val name: String, val arguments: List<PhaenonType> = emptyList()) : PhaenonType {
        /** The last segment of the name: `Foo` for `\\Ns\\Foo`. */
        val simple: String get() = name.substringAfterLast('\\')
        val qualified: Boolean get() = name.contains('\\')
    }

    /** `A|B`; `?A` is the union of `A` and `null`. */
    data class Union(val members: List<PhaenonType>) : PhaenonType

    /** `A&B`. */
    data class Intersection(val members: List<PhaenonType>) : PhaenonType

    /** `(A, B)`: a positional array (`array{A, B}` to PHPStan). */
    data class Tuple(val elements: List<PhaenonType>) : PhaenonType

    /** `Closure(A, B=, C...): R`; [qualified] for the `\Closure` spelling. */
    data class Closure(val qualified: Boolean, val parameters: List<Parameter>, val returnType: PhaenonType?) : PhaenonType

    /** A closure type's parameter: a type, optional (`T=`) or variadic (`T...`). */
    data class Parameter(val type: PhaenonType, val optional: Boolean, val variadic: Boolean)

    /** `array{id: int, name?: string, ...}`; [open] with the `...` that admits keys the shape does not name. */
    data class Shape(val entries: List<Entry>, val open: Boolean) : PhaenonType

    /** A shape's entry: its key as written, quotes included (`id`, `'a-b'`, `-1`), whether it may be absent, its type. */
    data class Entry(val key: String, val optional: Boolean, val type: PhaenonType)

    /** One entry of a declaration list `<in T of Bound = Default>`: the name's offset in the list text, the name, the bound, the default. */
    data class TypeParameter(val offset: Int, val name: String, val bound: PhaenonType?, val default: PhaenonType? = null)

    /**
     * A `type Name<…> = T;` declaration: the name and its offset in the declaration's text, the
     * type parameters (their offsets in that text too), and the type, null when it does not parse.
     */
    data class Alias(val name: String, val offset: Int, val parameters: List<TypeParameter>, val type: PhaenonType?)

    companion object {
        val NULL = Named("null")

        /** The type [text] spells, or null when it is not a type. */
        fun parse(text: String): PhaenonType? {
            val p = Parser(text)
            val type = p.union() ?: return null
            return if (p.atEnd()) type else null
        }

        /**
         * The declaration [text] holds, or null when it is no `type` declaration: the text of the
         * leaf [PhaenonLexer] erased one into, its docblock and the comments above it included.
         */
        fun alias(text: String): Alias? = Parser(text).alias()

        /**
         * The entries of a declaration list `<T, in K of Key, out V = int>`, in order. An entry whose
         * bound does not parse keeps its name and has no bound.
         */
        fun typeParameters(list: String): List<TypeParameter> {
            val open = list.indexOf('<')
            val close = list.lastIndexOf('>')
            if (open < 0 || close <= open) return emptyList()
            val entries = ArrayList<TypeParameter>()
            var depth = 0
            var start = open + 1
            for (k in open + 1 until close) {
                when (list[k]) {
                    '<', '(' -> depth++
                    '>', ')' -> depth--
                    ',' -> if (depth == 0) {
                        entry(list, start, k)?.let(entries::add)
                        start = k + 1
                    }
                }
            }
            entry(list, start, close)?.let(entries::add)
            return entries
        }

        /** One entry: the first word that is not a variance keyword is the name; `of` introduces the bound. */
        private fun entry(list: String, start: Int, end: Int): TypeParameter? {
            val text = list.substring(start, end)
            for (word in Regex("\\S+").findAll(text)) {
                if (word.value == "in" || word.value == "out") continue
                val name = word.value.takeWhile { it.isLetterOrDigit() || it == '_' }
                if (name.isEmpty()) return null
                val p = Parser(text.substring(word.range.first + name.length))
                val bound = if (p.peekWord() == "of") {
                    p.skipWord()
                    p.union()
                } else {
                    null
                }
                return TypeParameter(start + word.range.first, name, bound, p.default())
            }
            return null
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean {
            skipSpaces()
            return i >= s.length
        }

        /** `= Default` after a type parameter's name or bound, or null. */
        fun default(): PhaenonType? {
            skipSpaces()
            if (peek() != '=') return null
            i++
            return union()
        }

        /** `type Name<…> = T;`, after the comments above it. */
        fun alias(): Alias? {
            skipSpaces()
            if (!peekWord().equals("type", ignoreCase = true)) return null
            i += 4
            if (i >= s.length || !(s[i].isWhitespace() || s[i] == '/' || s[i] == '#')) return null
            skipSpaces()
            val offset = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            if (i == offset || s[offset].isDigit()) return null
            val name = s.substring(offset, i)
            skipSpaces()
            var parameters = emptyList<TypeParameter>()
            if (peek() == '<') {
                val open = i
                var depth = 0
                while (i < s.length) {
                    when (s[i]) {
                        '<', '(' -> depth++
                        '>', ')' -> depth--
                    }
                    i++
                    if (depth == 0) break
                }
                if (depth != 0) return null
                parameters = typeParameters(s.substring(open, i)).map { it.copy(offset = it.offset + open) }
                skipSpaces()
            }
            if (peek() != '=') return null
            i++
            val type = union()
            skipSpaces()
            return Alias(name, offset, parameters, type.takeIf { peek() == ';' || i >= s.length })
        }

        fun union(): PhaenonType? {
            skipSpaces()
            var nullable = false
            if (peek() == '?') {
                nullable = true
                i++
            }
            val members = ArrayList<PhaenonType>()
            var intersection = false
            var union = false
            while (true) {
                skipSpaces()
                // A fragment of a union erased around a native member starts or ends with its separator.
                if (peek() == '|' || peek() == '&') {
                    i++
                    continue
                }
                members.add(atom() ?: return null)
                skipSpaces()
                if (peek() == '|' || peek() == '&') {
                    if (peek() == '&') intersection = true else union = true
                    i++
                    skipSpaces()
                    if (i >= s.length || peek() == ')' || peek() == '>' || peek() == ',' || peek() == '=' || s.startsWith("...", i)) break
                    continue
                }
                break
            }
            if (nullable) members.add(NULL)
            return when {
                members.size == 1 -> members[0]
                intersection && !union && !nullable -> Intersection(members)
                else -> Union(members)
            }
        }

        private fun atom(): PhaenonType? {
            skipSpaces()
            if (peek() == '(') {
                i++
                val members = ArrayList<PhaenonType>()
                while (true) {
                    members.add(union() ?: return null)
                    skipSpaces()
                    when (peek()) {
                        ',' -> i++
                        ')' -> {
                            i++
                            break
                        }
                        else -> return null
                    }
                }
                // A tuple is a positional array; a parenthesised type is itself (a DNF group).
                return if (members.size == 1) members[0] else Tuple(members)
            }
            val name = name() ?: return null
            skipSpaces()
            if (peek() == '{' && name.equals("array", ignoreCase = true)) return shape()
            val arguments = if (peek() == '<') typeArguments() ?: return null else emptyList()
            if (name == "Closure" || name == "\\Closure") {
                skipSpaces()
                if (peek() == '(') return closure(name.startsWith("\\"))
            }
            return Named(name, arguments)
        }

        private fun typeArguments(): List<PhaenonType>? {
            i++ // <
            val arguments = ArrayList<PhaenonType>()
            while (true) {
                skipSpaces()
                if (peekWord() == "in" || peekWord() == "out") skipWord()
                arguments.add(union() ?: return null)
                skipSpaces()
                if (peekWord() == "of") {
                    skipWord()
                    union() ?: return null
                    skipSpaces()
                }
                if (peek() == '=') {
                    i++
                    union() ?: return null
                    skipSpaces()
                }
                when (peek()) {
                    ',' -> i++
                    '>' -> {
                        i++
                        return arguments
                    }
                    else -> return null
                }
            }
        }

        /** `{id: int, name?: string, ...}` after `array`, from its brace. */
        private fun shape(): PhaenonType? {
            i++ // {
            val entries = ArrayList<Entry>()
            while (true) {
                skipSpaces()
                if (peek() == '}') {
                    i++
                    return Shape(entries, open = false)
                }
                if (s.startsWith("...", i)) {
                    i += 3
                    skipSpaces()
                    if (peek() == ',') i++
                    skipSpaces()
                    if (peek() != '}') return null
                    i++
                    return Shape(entries, open = true)
                }
                val key = key() ?: return null
                skipSpaces()
                val optional = peek() == '?'
                if (optional) i++
                skipSpaces()
                if (peek() != ':') return null
                i++
                entries.add(Entry(key, optional, union() ?: return null))
                skipSpaces()
                when (peek()) {
                    ',' -> i++
                    '}' -> Unit
                    else -> return null
                }
            }
        }

        /** A shape's key as written: a name, a quoted string with its quotes, an integer. */
        private fun key(): String? {
            val start = i
            when {
                peek() == '\'' || peek() == '"' -> {
                    val quote = peek()
                    i++
                    while (i < s.length && s[i] != quote) i++
                    if (i >= s.length) return null
                    i++
                    return s.substring(start, i)
                }
                peek() == '-' || peek().isDigit() -> {
                    val negative = peek() == '-'
                    if (negative) i++
                    skipSpaces()
                    val digits = i
                    while (i < s.length && s[i].isDigit()) i++
                    if (i == digits) return null
                    return (if (negative) "-" else "") + s.substring(digits, i)
                }
            }
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            return if (i == start) null else s.substring(start, i)
        }

        private fun closure(qualified: Boolean): PhaenonType? {
            i++ // (
            skipSpaces()
            val parameters = ArrayList<Parameter>()
            if (peek() != ')') {
                while (true) {
                    val type = union() ?: return null
                    skipSpaces()
                    var optional = false
                    var variadic = false
                    if (s.startsWith("...", i)) {
                        i += 3
                        variadic = true
                    } else if (peek() == '=') {
                        i++
                        optional = true
                    }
                    parameters.add(Parameter(type, optional, variadic))
                    skipSpaces()
                    when (peek()) {
                        ',' -> i++
                        ')' -> break
                        else -> return null
                    }
                    skipSpaces()
                }
            }
            i++ // )
            skipSpaces()
            var returnType: PhaenonType? = null
            if (peek() == ':') {
                i++
                returnType = union() ?: return null
            }
            return Closure(qualified, parameters, returnType)
        }

        private fun name(): String? {
            val start = i
            if (peek() == '\\') i++
            while (true) {
                val wordStart = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                if (i == wordStart) return null
                if (peek() == '\\') {
                    i++
                    continue
                }
                break
            }
            return s.substring(start, i)
        }

        fun peekWord(): String {
            skipSpaces()
            var j = i
            while (j < s.length && s[j].isLetter()) j++
            return s.substring(i, j)
        }

        fun skipWord() {
            while (i < s.length && s[i].isLetter()) i++
            skipSpaces()
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        /** Blanks, and the comments the lexer passes over with them: a block, a docblock, a line's rest after `//` or `#`. */
        private fun skipSpaces() {
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    s.startsWith("//", i) || s[i] == '#' -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 }
                    else -> return
                }
            }
        }
    }
}

/** This type with each of its names put through [named], the names of its arguments first. */
fun PhaenonType.map(named: (PhaenonType.Named) -> PhaenonType): PhaenonType = when (this) {
    is PhaenonType.Union -> PhaenonType.Union(members.map { it.map(named) })
    is PhaenonType.Intersection -> PhaenonType.Intersection(members.map { it.map(named) })
    is PhaenonType.Tuple -> PhaenonType.Tuple(elements.map { it.map(named) })
    is PhaenonType.Closure -> PhaenonType.Closure(qualified, parameters.map { it.copy(type = it.type.map(named)) }, returnType?.map(named))
    is PhaenonType.Shape -> PhaenonType.Shape(entries.map { it.copy(type = it.type.map(named)) }, open)
    is PhaenonType.Named -> named(PhaenonType.Named(name, arguments.map { it.map(named) }))
}

/** Whether a name of this type, at any depth, satisfies [test]. */
fun PhaenonType.names(test: (PhaenonType.Named) -> Boolean): Boolean = when (this) {
    is PhaenonType.Union -> members.any { it.names(test) }
    is PhaenonType.Intersection -> members.any { it.names(test) }
    is PhaenonType.Tuple -> elements.any { it.names(test) }
    is PhaenonType.Closure -> parameters.any { it.type.names(test) } || returnType?.names(test) == true
    is PhaenonType.Shape -> entries.any { it.type.names(test) }
    is PhaenonType.Named -> test(this) || arguments.any { it.names(test) }
}

/**
 * This type in Phaenon's spelling, which [PhaenonType.parse] reads back into an equal tree: what
 * is stored of a type between the file that wrote it and the place that expands it. A nullable
 * type is the union it is, and a member that would merge with its neighbours is bracketed.
 */
fun PhaenonType.text(): String = when (this) {
    is PhaenonType.Union -> members.joinToString("|") { if (it is PhaenonType.Closure || it is PhaenonType.Intersection) "(${it.text()})" else it.text() }
    is PhaenonType.Intersection -> members.joinToString("&") { if (it is PhaenonType.Closure || it is PhaenonType.Union) "(${it.text()})" else it.text() }
    is PhaenonType.Tuple -> elements.joinToString(", ", "(", ")") { it.text() }
    is PhaenonType.Closure -> (if (qualified) "\\Closure" else "Closure") + parameters.joinToString(", ", "(", ")") { it.type.text() + if (it.variadic) "..." else if (it.optional) "=" else "" } +
        (returnType?.let { ": " + it.text() } ?: "")
    is PhaenonType.Shape -> shape(entries.map { it.key + (if (it.optional) "?" else "") + ": " + it.type.text() }, open)
    is PhaenonType.Named -> name + if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.text() }
}

/**
 * This type as a docblock spells it for PHPStan, and as PhpStorm reads one: the vocabulary the
 * compiler writes into the output (design/collections.md §4, §6.9). `list<T>` stays, `map<K, V>`
 * is `array<K, V>`, `bytes` is `string`, `class<T>` is `class-string<T>`, a tuple is the
 * positional shape `array{A, B}`, a closure type is `\Closure(A, B=, C...): R` with its return
 * bracketed when it is a union, and a shape that names no key is `array`. Class names are written
 * as they stand, so the type is to be qualified first; a bare name that is no keyword is a type
 * parameter nobody bound, `mixed`.
 */
fun PhaenonType.docblock(): String = when (this) {
    is PhaenonType.Union -> members.joinToString("|") { if (it is PhaenonType.Closure || it is PhaenonType.Intersection) "(${it.docblock()})" else it.docblock() }
    is PhaenonType.Intersection -> members.joinToString("&") { if (it is PhaenonType.Closure || it is PhaenonType.Union) "(${it.docblock()})" else it.docblock() }
    is PhaenonType.Tuple -> elements.joinToString(", ", "array{", "}") { it.docblock() }
    is PhaenonType.Closure -> "\\Closure" + parameters.joinToString(", ", "(", ")") { it.type.docblock() + if (it.variadic) "..." else if (it.optional) "=" else "" } +
        (returnType?.let { ": " + if (it is PhaenonType.Union || it is PhaenonType.Intersection) "(${it.docblock()})" else it.docblock() } ?: "")
    is PhaenonType.Shape -> if (entries.isEmpty() && open) "array" else shape(entries.map { it.key + (if (it.optional) "?" else "") + ": " + it.type.docblock() }, open)
    is PhaenonType.Named -> {
        val arguments = if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">") { it.docblock() }
        when {
            qualified -> name + arguments
            simple == "map" -> "array$arguments"
            simple == "bytes" -> "string"
            simple == "class" -> "class-string$arguments"
            simple.lowercase() in DOCBLOCK_WORDS -> name + arguments
            else -> "mixed"
        }
    }
}

private fun shape(entries: List<String>, open: Boolean): String = (if (open) entries + "..." else entries).joinToString(", ", "array{", "}")

/** The bare words a docblock type may hold as they are; any other bare name is a type parameter. */
private val DOCBLOCK_WORDS: Set<String> = setOf(
    "list", "int", "float", "string", "bool", "mixed", "null", "void", "never", "object", "array", "callable", "iterable",
    "true", "false", "self", "static", "parent", "resource", "integer", "double", "boolean", "scalar", "numeric",
)
