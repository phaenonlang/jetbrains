package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * Translates Phaenon type text into the `PhpType` PhpStorm reasons with.
 *
 * The translation follows the compiler's erasure (`design/collections.md` §2, `strings.md` §2,
 * `generics.md` §4):
 *
 * | Phaenon | PhpStorm |
 * |---|---|
 * | `list<T>` | `T[]` |
 * | `map<K, V>` | `V[]` — PhpStorm holds no key type for an array |
 * | `bytes` | `string` |
 * | `(A, B)` | `A[]|B[]` — the positional shape as PhpStorm can hold it |
 * | `class<T>` | `class-string<Bound>`, as PhpStorm reads a docblock `@return class-string<Bound>` |
 * | `Closure(A, B=, C...): R` | `\Closure` and the signature as PhpStorm encodes a docblock `\Closure(A, B, C): R` |
 * | a type parameter | its bound, `mixed` without one — what the compiler writes for it |
 * | `Foo<T>` | `\Ns\Foo` — type arguments of user classes are erased |
 *
 * A list, a map and bytes are a PHP array and a PHP string at runtime, and to PhpStorm they are
 * nothing more: every PHP array and string function, `foreach` and `[]` work on them as in PHP,
 * and so does `->`, which means what PHP means by it (roadmap D20).
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
        is PhaenonType.Closure -> closure(node)
        is PhaenonType.Named -> named(node)
    }

    private fun named(node: PhaenonType.Named): PhpType {
        val arguments = node.arguments.map(::type)
        if (!node.qualified) {
            when (node.simple) {
                "list", "map" -> return collection(arguments.lastOrNull())
                "bytes" -> return PhpType.STRING
                "class" -> return classString(arguments.firstOrNull())
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

    /**
     * `class<T>` is PHPStan's `class-string<T>`, and its type is the one PhpStorm gives a function
     * whose only return type is the docblock `@return class-string<Foo>`: a single
     * `\class-string<\Ns\Foo>`, on which `$c::m()`, `new $c`, `$c::CONST` resolve against the
     * class. A union of classes is one member with several arguments, `\class-string<\A,\B>`, as
     * PhpStorm reads `class-string<A|B>`. A type parameter stands for its bound; bare `class` and
     * an unbounded parameter leave the class open (`\class-string`).
     *
     * No `string` beside it: PhpStorm treats a class-string as a string wherever a string is
     * asked for (`PhpType.getTypes()` reads it as `\string`, and a `string` parameter accepts it),
     * and a second member would print as `string|string`, PhpStorm printing every class-string
     * as `string`. A `string` member is what PhpStorm adds for a native `string` return type next
     * to the docblock, which a Phaenon declaration does not have: `class<T>` is erased whole.
     */
    private fun classString(argument: PhpType?): PhpType {
        val classes = argument?.types?.filter { isClassName(it) }.orEmpty().sorted()
        if (classes.isEmpty()) return PhpType().add(PhpType.CLASS_STRING)
        return PhpType().add(PhpType.createParametrizedType(PhpType._CLASS_STRING, classes))
    }

    /**
     * `Closure(A, B=, C...): R` is `\Closure` plus the signature, encoded as PhpStorm encodes the
     * docblock type `\Closure(A, B, C): R` (`\Closure<#ᤓᤓ\A,…,\R>`), which its call checks and
     * call typing read. The variadic parameter is its element type, as PhpStorm's docblock
     * reading has it; the optional marker has no encoding and needs none: PhpStorm checks the
     * arguments that are there against the parameters that are, and never asks for a missing one.
     */
    private fun closure(node: PhaenonType.Closure): PhpType {
        val parts = node.parameters.map { PhpExpressionImpl.serializeAdvancedClosureParameter("", serialized(type(it.type))) }
        val returnType = node.returnType?.let(::type) ?: PhpType.MIXED
        return PhpType().add(PhpType.CLOSURE).add(PhpType.createParametrizedType(PhpType._CLOSURE, parts + serialized(returnType)))
    }

    private fun serialized(type: PhpType): String = Companion.serialized(type)

    /** A list or a map is the array of its element (value) type; without one, `array`. */
    private fun collection(element: PhpType?): PhpType = PhpType().add(element?.pluralise() ?: PhpType.ARRAY)

    private fun resolveClass(name: String): String {
        if (name.startsWith("\\")) return name
        val first = name.substringBefore('\\')
        val rest = name.substring(first.length)
        aliases[first]?.let { return "\\" + it.trimStart('\\') + rest }
        return if (namespace.isEmpty()) "\\$name" else "$namespace\\$name"
    }

    private fun isClassName(type: String): Boolean =
        type.startsWith("\\") && !PhpType.isPrimitiveType(type) && !PhpType.isPluralType(type) && !PhpType.hasParameterizedPart(type)

    companion object {
        /** A union inside a closure signature is one part: its members joined as PhpStorm joins them, in a stable order. */
        fun serialized(type: PhpType): String =
            type.typesWithParametrisedParts.sorted().joinToString(PhpExpressionImpl.CALLABLE_PARAMS_TYPES_SEPARATOR)

        /** `\Closure` with the signature PhpStorm reads from the docblock `\Closure(A, B): R`, from types it holds. */
        fun closureType(parameters: List<PhpType>, returnType: PhpType): PhpType {
            val parts = parameters.map { PhpExpressionImpl.serializeAdvancedClosureParameter("", serialized(it)) }
            return PhpType().add(PhpType.CLOSURE).add(PhpType.createParametrizedType(PhpType._CLOSURE, parts + serialized(returnType)))
        }

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
