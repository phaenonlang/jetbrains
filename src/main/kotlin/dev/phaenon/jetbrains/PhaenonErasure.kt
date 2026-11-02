package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * The types PHP compares when one method overrides another, as the compiler writes them for a
 * Phaenon signature (`design/generics.md` §4.1, §4.3, §11).
 *
 * A type is a set of [Atom]s. A native atom is what PHP sees: `list<User>` and `(A, B)` are
 * `array`, `class<T>` and `bytes` are `string`, `Closure(A): R` is `\Closure`, `Box<User>` is
 * `\Ns\Box`. A class's type parameter stays an atom of its own, [Atom.Parameter], carrying its
 * bound, so that substitution can find it; what remains of it after substitution is compared by its
 * bound, as the checker compares overrides, and the compiler writes the bound (`mixed` without one).
 *
 * Comparing the parent's signature as the child sees it takes substitution: in
 * `class Repo<T of Event> implements Store<T>`, the parent's `save(T $x)` is the child's
 * `save(T $x)`, whatever `Store` bounds its own `T` with. [Signature] builds both sides that way:
 * the parent's type parameters are replaced by the type arguments written along the `extends` /
 * `implements` path from the child, and only then is anything compared.
 */
object PhaenonErasure {

    sealed interface Atom {
        /** A native type: a class name (`\Ns\Foo`) or a lowercase keyword (`int`, `array`, `null`, `mixed`, `void`, `static`). */
        data class Native(val name: String) : Atom {
            val isClass: Boolean get() = name.startsWith("\\")
        }

        /** A type parameter of the class [owner] (its FQN); [bound] is null without one. */
        data class Parameter(val owner: String, val name: String, val bound: Set<Atom>?) : Atom
    }

    val MIXED: Set<Atom> = setOf(Atom.Native("mixed"))

    /** One side of an override: a method's parameter types and return type, erased. */
    class Signature(val parameters: List<Set<Atom>>, val returnType: Set<Atom>?)

    /** Whether [method]'s own signature has a Phaenon type the compiler erases (`T`, `list<…>`, `class<T>`, …). */
    fun hasErasedTypes(method: Function): Boolean =
        PhaenonErasedTypes.isPhaenon(method) &&
            (PhaenonErasedTypes.returnType(method).isNotEmpty() || method.parameters.any { PhaenonErasedTypes.beforeName(it).isNotEmpty() })

    /** [method]'s signature as its own class sees it: the class's type parameters are themselves. */
    fun ownSignature(method: Function): Signature = signature(method, inScope(method))

    /**
     * [parent]'s signature as [child] sees it: the type parameters of [parent]'s class replaced by
     * the arguments written on the way from [child]. A parameter without an argument (a raw
     * `implements Store`) stands for its bound, as the compiler erases it.
     */
    fun inheritedSignature(parent: Method, child: PhpClass): Signature {
        val declaring = parent.containingClass ?: return nativeSignature(parent)
        val substitution = substitution(child, declaring).orEmpty()
        val own = inScope(parent)
        return signature(parent) { name ->
            own(name)?.let { atoms ->
                val atom = atoms.singleOrNull() as? Atom.Parameter
                if (atom != null && atom.owner == declaring.fqn) substitution[name] ?: erase(atoms) else atoms
            }
        }
    }

    /** [function]'s signature as PHP sees it: every type parameter written as its bound. */
    fun nativeSignature(function: Function): Signature {
        val own = inScope(function)
        return signature(function) { name -> own(name)?.let(::erase) }
    }

    private fun signature(function: Function, typeParameter: (String) -> Set<Atom>?): Signature {
        val scope = Scope(function, typeParameter)
        return Signature(function.parameters.map { scope.parameter(it) }, scope.returnType(function))
    }

    /** A type parameter replaced by what it erases to: its bound, `mixed` without one. */
    fun erase(type: Set<Atom>): Set<Atom> = type.flatMapTo(LinkedHashSet()) { atom ->
        when (atom) {
            is Atom.Native -> setOf(atom)
            is Atom.Parameter -> atom.bound?.let(::erase) ?: MIXED
        }
    }

    // ---------------------------------------------------------------- subtyping

    /**
     * Whether every value of [sub] is a [sup], as the checker compares an override (`P2502`): after
     * substitution, a type parameter stands for its bound on either side — `withId(T $id)` with
     * `T of Id` accepts what `withId(Id $id)` accepts — and the native types follow PHP's variance
     * rules.
     */
    fun isSubtype(sub: Set<Atom>, sup: Set<Atom>, index: PhpIndex): Boolean {
        val natives = erase(sup)
        return sub.all { atom -> atom is Atom.Parameter && atom in sup || erase(setOf(atom)).all { isSubtype(it as Atom.Native, natives, index) } }
    }

    private fun isSubtype(atom: Atom.Native, sup: Set<Atom>, index: PhpIndex): Boolean {
        val natives = sup.map { (it as Atom.Native).name }
        val name = atom.name
        return when {
            "mixed" in natives || name == "never" -> true
            name == "mixed" -> false
            natives.any { it.equals(name, ignoreCase = true) } -> true
            name == "true" || name == "false" -> "bool" in natives
            name == "array" -> "iterable" in natives
            atom.isClass -> isClassSubtype(name, natives, index)
            else -> false
        }
    }

    private fun isClassSubtype(name: String, sup: List<String>, index: PhpIndex): Boolean {
        if ("object" in sup) return true
        // An unknown class cannot be judged; PHP would not load the file without it either.
        if (index.getAnyByFQN(name).isEmpty()) return true
        for (s in sup) {
            when {
                s == "callable" -> if (name.equals("\\Closure", ignoreCase = true) || PhpType.findSuper("\\Closure", name, index)) return true
                s == "iterable" -> if (PhpType.findSuper("\\Traversable", name, index)) return true
                s.startsWith("\\") -> if (index.getAnyByFQN(s).isEmpty() || PhpType.findSuper(s, name, index)) return true
            }
        }
        return false
    }

    /** Whether two types erase to the same native type: the same names, whatever the case. */
    fun sameNative(a: Set<Atom>, b: Set<Atom>): Boolean {
        fun names(type: Set<Atom>) = erase(type).map { (it as Atom.Native).name.lowercase() }.toSet()
        return names(a) == names(b)
    }

    /** How PhpStorm prints a type in its own messages: `\App\Agg`, `int`, `?\App\Agg`, `int|string`. */
    fun present(type: Set<Atom>): String {
        val names = erase(type).map { (it as Atom.Native).name }.distinct()
        val nonNull = names.filter { it != "null" }
        return if (names.size == 2 && nonNull.size == 1) "?${nonNull[0]}" else names.joinToString("|")
    }

    // ---------------------------------------------------------------- reading a signature

    /** Type parameter names to atoms, for the scope of one function and its class. */
    private class Scope(private val context: PsiElement, private val typeParameter: (String) -> Set<Atom>?) {

        private val namespace: String by lazy { PhpPsiUtil.getParentOfClass(context, PhpNamespace::class.java)?.fqn?.trimEnd('\\') ?: "" }
        private val aliases: Map<String, String> by lazy {
            PhpCodeInsightUtil.findScopeForUseOperator(context)?.let { PhpCodeInsightUtil.getAliasesInScope(it) } ?: emptyMap()
        }
        private val enclosingClass: String? by lazy { (context as? PhpClass ?: (context as? Method)?.containingClass)?.fqn }

        fun parameter(parameter: Parameter): Set<Atom> {
            val result = LinkedHashSet<Atom>()
            parameter.typeDeclaration?.type?.let { native(it, result) }
            erased(PhaenonErasedTypes.beforeNameParts(parameter), result)
            return result.ifEmpty { MIXED }
        }

        fun returnType(function: Function): Set<Atom>? {
            val result = LinkedHashSet<Atom>()
            function.typeDeclaration?.type?.let { native(it, result) }
            erased(PhaenonErasedTypes.returnTypeParts(function), result)
            return result.ifEmpty { null }
        }

        /**
         * The members a declaration's erased leaves add. The type arguments of a native name
         * (`<TResult>` of `Command<TResult>`, `<K, V>` of `array<K, V>`) erase with the name and add
         * nothing; a leaf that does not parse adds nothing either, and the native part stands.
         */
        private fun erased(parts: List<String>, into: MutableSet<Atom>) {
            for (part in parts) {
                if (part.trimStart('|', '&', ' ').startsWith("<")) continue
                PhaenonType.parse(part)?.let { into.addAll(atoms(it)) }
            }
        }

        /** The native part PhpStorm parsed itself, already resolved: `\App\Foo`, `\int`, `\null`. */
        private fun native(type: PhpType, into: MutableSet<Atom>) {
            for (t in type.types) {
                val name = t.removeSuffix("[]")
                into.add(if (PhpType.isPrimitiveType(name) || name.equals("\\static", ignoreCase = true) || name.equals("\\self", ignoreCase = true)) {
                    keyword(name.trimStart('\\').lowercase())
                } else {
                    Atom.Native(name)
                })
            }
        }

        fun atoms(node: PhaenonType): Set<Atom> = when (node) {
            is PhaenonType.Union -> node.members.flatMapTo(LinkedHashSet()) { atoms(it) }
            is PhaenonType.Intersection -> node.members.flatMapTo(LinkedHashSet()) { atoms(it) }
            is PhaenonType.Tuple -> setOf(Atom.Native("array"))
            is PhaenonType.Closure -> setOf(Atom.Native("\\Closure"))
            is PhaenonType.Named -> named(node)
        }

        private fun named(node: PhaenonType.Named): Set<Atom> {
            if (!node.qualified) {
                val lower = node.simple.lowercase()
                when (lower) {
                    "list", "map", "array" -> return setOf(Atom.Native("array"))
                    "class", "bytes" -> return setOf(Atom.Native("string"))
                }
                typeParameter(node.simple)?.let { return it }
                if (lower in KEYWORDS) return setOf(keyword(lower))
            }
            return setOf(Atom.Native(resolveClass(node.name)))
        }

        /** `self` and `static` stand for the class declaring the method. */
        private fun keyword(lower: String): Atom = when (lower) {
            "self", "static" -> enclosingClass?.let { Atom.Native(it) } ?: Atom.Native("object")
            "integer" -> Atom.Native("int")
            "boolean" -> Atom.Native("bool")
            "double" -> Atom.Native("float")
            else -> Atom.Native(lower)
        }

        private fun resolveClass(name: String): String {
            if (name.startsWith("\\")) return name
            val first = name.substringBefore('\\')
            val rest = name.substring(first.length)
            aliases[first]?.let { return "\\" + it.trimStart('\\') + rest }
            return if (namespace.isEmpty()) "\\$name" else "$namespace\\$name"
        }
    }

    /**
     * The type parameters in scope at [element]: a class's as [Atom.Parameter]s carrying their
     * bounds, a function's or method's as their bounds. Class parameters are what substitution
     * replaces; a method's own type parameters are compared at their bounds, as PHP sees them.
     */
    private fun inScope(element: PsiElement): (String) -> Set<Atom>? {
        val declared = PhaenonErasedTypes.typeParameterBounds(element)
        val cache = HashMap<String, Set<Atom>>()
        val visiting = HashSet<String>()
        fun resolve(name: String): Set<Atom>? {
            val parameter = declared[name] ?: return null
            cache[name]?.let { return it }
            if (!visiting.add(name)) return MIXED
            try {
                val bound = parameter.bound?.let { Scope(element, ::resolve).atoms(it) }
                val owner = parameter.owner
                val result = if (owner is PhpClass) setOf(Atom.Parameter(owner.fqn, name, bound)) else bound?.let(::erase) ?: MIXED
                cache[name] = result
                return result
            } finally {
                visiting.remove(name)
            }
        }
        return ::resolve
    }

    // ---------------------------------------------------------------- substitution

    /**
     * The type parameters of [ancestor] as [child] sees them: each argument written on the
     * `extends` / `implements` path from [child], read in the scope of the class that wrote it.
     * Null when [ancestor] is [child] or no path is found; a parameter without an argument is left
     * out (it stands for its bound).
     */
    private fun substitution(child: PhpClass, ancestor: PhpClass): Map<String, Set<Atom>>? {
        if (child.fqn == ancestor.fqn) return null
        return substitution(child, inScope(child), ancestor, HashSet())
    }

    private fun substitution(from: PhpClass, fromScope: (String) -> Set<Atom>?, ancestor: PhpClass, visited: MutableSet<String>): Map<String, Set<Atom>>? {
        if (!visited.add(from.fqn)) return null
        val scope = Scope(from, fromScope)
        for (reference in supertypeReferences(from)) {
            val parent = declaration(reference) ?: continue
            val arguments = typeArguments(reference)
            val names = PhaenonErasedTypes.typeParameterBounds(parent).values.filter { it.owner === parent }.map { it.name }
            val parentSubstitution = HashMap<String, Set<Atom>>()
            for ((k, name) in names.withIndex()) {
                arguments.getOrNull(k)?.let { parentSubstitution[name] = scope.atoms(it) }
            }
            if (parent.fqn.equals(ancestor.fqn, ignoreCase = true)) return parentSubstitution
            val parentOwn = inScope(parent)
            val parentScope: (String) -> Set<Atom>? = { name -> parentSubstitution[name] ?: parentOwn(name)?.let(::erase) }
            substitution(parent, parentScope, ancestor, visited)?.let { return it }
        }
        return null
    }

    /** The class a supertype reference names, its `.phn` declaration first: only that one writes type arguments. */
    private fun declaration(reference: ClassReference): PhpClass? {
        val fqn = reference.fqn ?: return null
        val candidates = PhpIndex.getInstance(reference.project).getAnyByFQN(fqn)
        return candidates.firstOrNull { PhaenonErasedTypes.isPhaenon(it) } ?: candidates.firstOrNull()
    }

    /** The class references of `extends` and `implements`, in order. */
    private fun supertypeReferences(klass: PhpClass): List<ClassReference> =
        (klass.extendsList.referenceElements + klass.implementsList.referenceElements)

    /** The type arguments written after a supertype reference: `Store<T>` in `implements Store<T>`. */
    private fun typeArguments(reference: ClassReference): List<PhaenonType> {
        var leaf: PsiElement? = PsiTreeUtil.nextLeaf(reference, true)
        while (leaf is PsiWhiteSpace) leaf = PsiTreeUtil.nextLeaf(leaf, true)
        if (leaf == null || !PhaenonTokenTypes.isErased(leaf)) return emptyList()
        val text = leaf.text.trim()
        if (!text.startsWith("<")) return emptyList()
        return (PhaenonType.parse("X$text") as? PhaenonType.Named)?.arguments.orEmpty()
    }

    private val KEYWORDS = setOf(
        "int", "integer", "float", "double", "string", "bool", "boolean", "true", "false", "null", "mixed", "void", "never",
        "object", "array", "callable", "iterable", "resource", "self", "static",
    )
}
