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
 * | a type parameter | its bound, `mixed` without one — what the compiler writes for it |
 * | `Foo<T>` | `\Ns\Foo` — type arguments of user classes are erased |
 *
 * The array half keeps every PHP array function, `foreach` and `[]` working as before; the
 * `\Phaenon\Internal\*Methods` half is a pseudo-class bundled with the plugin whose instance
 * methods are the stdlib's functions without their receiver parameter, so `->map(...)` resolves,
 * completes and type-checks through PhpStorm's own machinery.
 *
 * A type parameter reads as its bound because that is all the code may assume of it: inside
 * `class Repo<T of Aggregate>`, `$aggregate->id()` resolves against `Aggregate`, as PHPStan
 * resolves it against the template's bound and as the emitted `save(Aggregate $aggregate)` says.
 */
class PhaenonTypeText(private val context: PsiElement, private val typeParameters: Map<String, PhaenonErasedTypes.TypeParameterInScope>) {

    private val namespace: String by lazy {
        PhpPsiUtil.getParentOfClass(context, PhpNamespace::class.java)?.fqn?.trimEnd('\\') ?: ""
    }

    private val aliases: Map<String, String> by lazy {
        val scope = PhpCodeInsightUtil.findScopeForUseOperator(context) ?: return@lazy emptyMap()
        PhpCodeInsightUtil.getAliasesInScope(scope)
    }

    private val enclosingClass: String? by lazy { PhpPsiUtil.getParentOfClass(context, PhpClass::class.java)?.fqn }

    /** The bounds being translated: `<T of list<T>>` must not recurse. */
    private val visiting = HashSet<String>()

    /** The type the text denotes, or null when the text is not a type. */
    fun parse(text: String): PhpType? = PhaenonType.parse(text)?.let(::type)

    /** The type a parsed Phaenon type denotes. */
    fun type(node: PhaenonType): PhpType = when (node) {
        is PhaenonType.Union -> PhpType().also { t -> node.members.forEach { t.add(type(it)) } }
        is PhaenonType.Intersection -> PhpType().also { t -> node.members.forEach { t.add(type(it)) } }
        // A tuple is a positional array.
        is PhaenonType.Tuple -> PhpType().also { t -> node.elements.forEach { t.add(type(it).pluralise()) } }
        is PhaenonType.Closure -> PhpType.CLOSURE
        is PhaenonType.Named -> named(node)
    }

    private fun named(node: PhaenonType.Named): PhpType {
        val arguments = node.arguments.map(::type)
        if (!node.qualified) {
            when (node.simple) {
                "list" -> return collection(LIST_METHODS, arguments.takeLast(1), arguments.lastOrNull())
                "map" -> return collection(MAP_METHODS, arguments, arguments.lastOrNull())
                "bytes" -> return PhpType().add(PhpType.STRING).add(BYTES_METHODS)
                "class" -> return PhpType().add(PhpType.createParametrizedType("\\class-string", arguments.map { it.toString() }))
                "iterable" -> return PhpType.ITERABLE
            }
            PRIMITIVES[node.simple.lowercase()]?.let { return it }
            typeParameters[node.simple]?.let { return bound(it) }
            if (node.simple == "self" || node.simple == "static") return enclosingClass?.let { PhpType().add(it) } ?: PhpType.OBJECT
        }
        return PhpType().add(resolveClass(node.name))
    }

    /** A type parameter is what its bound is; `mixed` when it has none. */
    private fun bound(parameter: PhaenonErasedTypes.TypeParameterInScope): PhpType {
        val bound = parameter.bound ?: return PhpType.MIXED
        if (!visiting.add(parameter.name)) return PhpType.MIXED
        try {
            return type(bound)
        } finally {
            visiting.remove(parameter.name)
        }
    }

    private fun collection(pseudoClass: String, parameters: List<PhpType>, element: PhpType?): PhpType {
        val result = PhpType()
        result.add(element?.pluralise() ?: PhpType.ARRAY)
        result.add(PhpType.createParametrizedType(pseudoClass, parameters.map { it.toString() }))
        return result
    }

    private fun resolveClass(name: String): String {
        if (name.startsWith("\\")) return name
        val first = name.substringBefore('\\')
        val rest = name.substring(first.length)
        aliases[first]?.let { return "\\" + it.trimStart('\\') + rest }
        return if (namespace.isEmpty()) "\\$name" else "$namespace\\$name"
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
        fun parameterNames(list: String): List<String> = PhaenonType.typeParameters(list).map { it.name }

        /**
         * The type parameters a declaration list `<T, in K of Key, out V>` declares: the offset
         * of each name in [list] and the name.
         */
        fun parameters(list: String): List<Pair<Int, String>> = PhaenonType.typeParameters(list).map { it.offset to it.name }
    }
}
