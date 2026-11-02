package dev.phaenon.jetbrains

/**
 * Phaenon type text as a tree, before any name is resolved.
 *
 * The same text is read two ways: [PhaenonTypeText] turns it into the `PhpType` the editor reasons
 * with (`list<User>` → `User[]` and a pseudo-class), [PhaenonErasure] into the native type the
 * compiler writes for it (`list<User>` → `array`), which is what PHP compares when a method
 * overrides another. The parser accepts the fragments [PhaenonErasedTypes] collects: the erased
 * members of a union around a native one start or end with their separator (`|list<int>`).
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

    /** One entry of a declaration list `<in T of Bound = Default>`: the name's offset in the list text, the name, the bound. */
    data class TypeParameter(val offset: Int, val name: String, val bound: PhaenonType?)

    companion object {
        val NULL = Named("null")

        /** The type [text] spells, or null when it is not a type. */
        fun parse(text: String): PhaenonType? {
            val p = Parser(text)
            val type = p.union() ?: return null
            return if (p.atEnd()) type else null
        }

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
                val rest = text.substring(word.range.first + name.length)
                val bound = Regex("^\\s*of\\s").find(rest)?.let { of ->
                    val p = Parser(rest.substring(of.range.last + 1))
                    p.union()
                }
                return TypeParameter(start + word.range.first, name, bound)
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

        private fun peekWord(): String {
            var j = i
            while (j < s.length && s[j].isLetter()) j++
            return s.substring(i, j)
        }

        private fun skipWord() {
            while (i < s.length && s[i].isLetter()) i++
            skipSpaces()
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
