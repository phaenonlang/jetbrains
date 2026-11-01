package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * Translates Phaenon type text into the `PhpType` PhpStorm reasons with.
 *
 * The translation follows the compiler's erasure (`design/collections.md` §2, `strings.md` §2,
 * `generics.md` §4) and adds the editor's view of the methods Phaenon puts on values:
 *
 * | Phaenon | PhpStorm |
 * |---|---|
 * | `list<T>` | `T[]` and `\Phaenon\Internal\ListMethods<T>` |
 * | `map<K, V>` | `V[]` and `\Phaenon\Internal\MapMethods<K, V>` |
 * | `bytes` | `string` and `\Phaenon\Internal\BytesMethods` |
 * | `(A, B)` | `A[]|B[]` — the positional shape as PhpStorm can hold it |
 * | `class<T>` | `class-string<T>` |
 * | `Closure(A): R` | `\Closure` |
 * | a type parameter | `mixed` |
 * | `Foo<T>` | `\Ns\Foo` — type arguments of user classes are erased |
 *
 * The array half keeps every PHP array function, `foreach` and `[]` working as before; the
 * `\Phaenon\Internal\*Methods` half is a pseudo-class bundled with the plugin whose instance
 * methods are the stdlib's functions without their receiver parameter, so `->map(...)` resolves,
 * completes and type-checks through PhpStorm's own machinery.
 */
class PhaenonTypeText(private val context: PsiElement, private val typeParameters: Set<String>) {

    private val namespace: String by lazy {
        PhpPsiUtil.getParentOfClass(context, PhpNamespace::class.java)?.fqn?.trimEnd('\\') ?: ""
    }

    private val aliases: Map<String, String> by lazy {
        val scope = PhpCodeInsightUtil.findScopeForUseOperator(context) ?: return@lazy emptyMap()
        PhpCodeInsightUtil.getAliasesInScope(scope)
    }

    private val enclosingClass: String? by lazy { PhpPsiUtil.getParentOfClass(context, PhpClass::class.java)?.fqn }

    /** The type the text denotes, or null when the text is not a type. */
    fun parse(text: String): PhpType? {
        val p = Parser(text)
        val type = p.union() ?: return null
        return if (p.atEnd()) type else null
    }

    private inner class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean {
            skipSpaces()
            return i >= s.length
        }

        fun union(): PhpType? {
            val result = PhpType()
            skipSpaces()
            var nullable = false
            if (peek() == '?') {
                nullable = true
                i++
            }
            while (true) {
                skipSpaces()
                // A fragment of a union erased around a native member starts or ends with its separator.
                if (peek() == '|' || peek() == '&') {
                    i++
                    continue
                }
                val member = atom() ?: return null
                result.add(member)
                skipSpaces()
                if (peek() == '|' || peek() == '&') {
                    i++
                    skipSpaces()
                    if (i >= s.length || peek() == ')' || peek() == '>' || peek() == ',') break
                    continue
                }
                break
            }
            if (nullable) result.add(PhpType.NULL)
            return result
        }

        private fun atom(): PhpType? {
            skipSpaces()
            if (peek() == '(') {
                i++
                val members = ArrayList<PhpType>()
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
                // A tuple is a positional array; a parenthesised type is itself.
                if (members.size == 1) return members[0]
                return PhpType().also { t -> members.forEach { t.add(it.pluralise()) } }
            }
            val name = name() ?: return null
            skipSpaces()
            val args = if (peek() == '<') typeArguments() ?: return null else emptyList()
            if (name == "Closure" || name == "\\Closure") {
                skipSpaces()
                if (peek() == '(') {
                    if (!skipClosureSignature()) return null
                }
                return PhpType.CLOSURE
            }
            return when (name) {
                "list" -> collection(LIST_METHODS, args.takeLast(1), args.lastOrNull())
                "map" -> collection(MAP_METHODS, args, args.lastOrNull())
                "bytes" -> PhpType().add(PhpType.STRING).add(BYTES_METHODS)
                "class" -> PhpType().add(PhpType.createParametrizedType("\\class-string", args.map { it.toString() }))
                "iterable" -> PhpType.ITERABLE
                else -> named(name)
            }
        }

        private fun collection(pseudoClass: String, parameters: List<PhpType>, element: PhpType?): PhpType {
            val result = PhpType()
            result.add(element?.pluralise() ?: PhpType.ARRAY)
            result.add(PhpType.createParametrizedType(pseudoClass, parameters.map { it.toString() }))
            return result
        }

        private fun named(name: String): PhpType {
            val simple = name.substringAfterLast('\\')
            if (!name.contains('\\')) {
                PRIMITIVES[simple.lowercase()]?.let { return it }
                if (simple in typeParameters) return PhpType.MIXED
                if (simple == "self" || simple == "static") return enclosingClass?.let { PhpType().add(it) } ?: PhpType.OBJECT
            }
            return PhpType().add(resolveClass(name))
        }

        private fun resolveClass(name: String): String {
            if (name.startsWith("\\")) return name
            val first = name.substringBefore('\\')
            val rest = name.substring(first.length)
            aliases[first]?.let { return "\\" + it.trimStart('\\') + rest }
            return if (namespace.isEmpty()) "\\$name" else "$namespace\\$name"
        }

        private fun typeArguments(): List<PhpType>? {
            i++ // <
            val args = ArrayList<PhpType>()
            while (true) {
                skipSpaces()
                if (peekWord() == "in" || peekWord() == "out") skipWord()
                args.add(union() ?: return null)
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
                        return args
                    }
                    else -> return null
                }
            }
        }

        private fun skipClosureSignature(): Boolean {
            i++ // (
            skipSpaces()
            if (peek() != ')') {
                while (true) {
                    union() ?: return false
                    skipSpaces()
                    if (s.startsWith("...", i)) i += 3 else if (peek() == '=') i++
                    skipSpaces()
                    when (peek()) {
                        ',' -> i++
                        ')' -> break
                        else -> return false
                    }
                }
            }
            i++ // )
            skipSpaces()
            if (peek() == ':') {
                i++
                union() ?: return false
            }
            return true
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

    companion object {
        const val LIST_METHODS = "\\Phaenon\\Internal\\ListMethods"
        const val MAP_METHODS = "\\Phaenon\\Internal\\MapMethods"
        const val STR_METHODS = "\\Phaenon\\Internal\\StrMethods"
        const val BYTES_METHODS = "\\Phaenon\\Internal\\BytesMethods"

        private val PRIMITIVES: Map<String, PhpType> = mapOf(
            "int" to PhpType.INT, "integer" to PhpType.INT, "float" to PhpType.FLOAT, "double" to PhpType.FLOAT,
            "string" to PhpType.STRING, "bool" to PhpType.BOOLEAN, "boolean" to PhpType.BOOLEAN,
            "true" to PhpType.TRUE, "false" to PhpType.FALSE, "null" to PhpType.NULL, "mixed" to PhpType.MIXED,
            "void" to PhpType.VOID, "never" to PhpType.NEVER, "object" to PhpType.OBJECT, "array" to PhpType.ARRAY,
            "callable" to PhpType.CALLABLE, "iterable" to PhpType.ITERABLE, "resource" to PhpType.RESOURCE,
        )

        /** The parameter names of a declaration list: `<in TIn, out TOut, K of int|string = string>` → TIn, TOut, K. */
        fun parameterNames(list: String): List<String> = parameters(list).map { it.second }

        /**
         * The type parameters a declaration list `<T, in K of Key, out V>` declares: the offset
         * of each name in [list] and the name.
         */
        fun parameters(list: String): List<Pair<Int, String>> {
            val open = list.indexOf('<')
            val close = list.lastIndexOf('>')
            if (open < 0 || close <= open) return emptyList()
            val names = ArrayList<Pair<Int, String>>()
            var depth = 0
            var start = open + 1
            for (k in open + 1 until close) {
                when (list[k]) {
                    '<', '(' -> depth++
                    '>', ')' -> depth--
                    ',' -> if (depth == 0) {
                        parameterName(list, start, k)?.let(names::add)
                        start = k + 1
                    }
                }
            }
            parameterName(list, start, close)?.let(names::add)
            return names
        }

        /** The name declared in one entry of the list: the first word that is not a variance keyword. */
        private fun parameterName(list: String, start: Int, end: Int): Pair<Int, String>? {
            for (word in Regex("\\S+").findAll(list.substring(start, end))) {
                if (word.value == "in" || word.value == "out") continue
                val name = word.value.takeWhile { it.isLetterOrDigit() || it == '_' }
                return if (name.isEmpty()) null else (start + word.range.first) to name
            }
            return null
        }
    }
}
