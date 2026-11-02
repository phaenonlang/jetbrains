package dev.phaenon.jetbrains

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpClassHierarchyUtils
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor

/**
 * Signature compatibility for overrides that involve a type the Phaenon compiler erases, in
 * PhpStorm's words: "Declaration must be compatible with …" and "Return type declaration must be
 * compatible with … : …".
 *
 * PhpStorm's own check (`PhpHierarchyChecksInspection`) compares `Parameter.getDeclaredType()`,
 * which is read from the type declaration in the PSI or its stub, and no extension point
 * contributes to it. An erased type leaves no declaration, so to PhpStorm the interface's
 * `save(T $aggregate)` is `save(mixed $aggregate)` and every typed implementation narrows it. Its
 * findings on such a pair are dropped ([PhaenonHighlightFilter]) and this inspection takes the
 * pair over, with the types the compiler writes and the checker compares (`design/generics.md`
 * §4.3, §11; `P2502`):
 *
 * - The parent's signature is read as the child sees it: its class's type parameters replaced by
 *   the arguments written on the `extends` / `implements` path ([PhaenonErasure.inheritedSignature]).
 *   `AbstractEventSourcedRepository<Id, T of EventSourcedAggregate> implements
 *   AggregateRepository<Id, T>` may declare `save(T $aggregate)`: the parent's `T` is its own.
 * - A type parameter is compared as itself where both sides name it, and by its bound otherwise.
 * - Parameters are contravariant and returns covariant, as in PHP; counts, optional, variadic and
 *   by-reference parameters follow PHP's rules too.
 *
 * Whatever passes here compiles: the override-erasure rule (§4.3) widens a parameter to the
 * parent's erased type and narrows a return to it, so PHP sees `save(Aggregate $aggregate)` on
 * both sides. Whatever fails is a real incompatibility the checker reports as well. A pair with
 * no erased type on either side stays PhpStorm's.
 */
class PhaenonHierarchyInspection : LocalInspectionTool() {

    override fun getShortName(): String = SHORT_NAME

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PhpElementVisitor() {
        override fun visitPhpMethod(method: Method) {
            for ((element, message) in problems(method)) {
                // A parameter whose type was erased starts at the erased type, before the PSI element.
                val start = (element as? Parameter)?.let { PhaenonErasedTypes.typeLeaf(it) }?.takeIf { it.textRange.startOffset < element.textRange.startOffset } ?: element
                val descriptor = holder.manager.createProblemDescriptor(start, element, message, ProblemHighlightType.GENERIC_ERROR, isOnTheFly, *LocalQuickFix.EMPTY_ARRAY)
                descriptor.problemGroup = PROBLEMS
                holder.registerProblem(descriptor)
            }
        }
    }

    companion object {
        const val SHORT_NAME = "PhaenonHierarchyChecks"

        /**
         * The group this inspection's findings carry. They read as PhpStorm's own, which the filter
         * drops on the same overrides; the group is how it tells them apart (the inspection's id is
         * not yet on a finding when filters see it).
         */
        val PROBLEMS: ProblemGroup = ProblemGroup { SHORT_NAME }

        /** The methods [method] overrides or implements, as PHP checks them: no private parent, a constructor only against an abstract or interface one. */
        fun superMethods(method: Method): List<Method> {
            if (method.containingClass == null) return emptyList()
            val result = ArrayList<Method>()
            PhpClassHierarchyUtils.processSuperMethods(method) { parent, _, _ ->
                val parentClass = parent.containingClass
                val checked = when {
                    parentClass == null || parent === method -> false
                    parent.modifier.isPrivate && !parentClass.isInterface -> false
                    method.name.equals("__construct", ignoreCase = true) -> parent.isAbstract || parentClass.isInterface
                    else -> true
                }
                if (checked) result.add(parent)
                true
            }
            return result
        }

        /**
         * Whether [method]'s overrides are this inspection's to judge rather than PhpStorm's:
         * [method] or a method it overrides has a type the compiler erases.
         */
        fun owns(method: Method): Boolean =
            PhaenonErasure.hasErasedTypes(method) || superMethods(method).any { PhaenonErasure.hasErasedTypes(it) }

        /** Each incompatibility of [method] with a method it overrides: where to report it, and the message. */
        fun problems(method: Method): List<Pair<PsiElement, String>> {
            val klass = method.containingClass ?: return emptyList()
            val parents = superMethods(method)
            if (!PhaenonErasure.hasErasedTypes(method) && parents.none { PhaenonErasure.hasErasedTypes(it) }) return emptyList()
            val index = PhpIndex.getInstance(method.project)
            val own = PhaenonErasure.ownSignature(method)
            val result = ArrayList<Pair<PsiElement, String>>()
            for (parent in parents) {
                val inherited = PhaenonErasure.inheritedSignature(parent, klass)
                declarationProblem(method, own, parent, inherited, index)?.let { element ->
                    result.add(element to PhpBundle.message("declaration.must.be.compatible.with.0", describe(parent)))
                }
                returnProblem(method, own, parent, inherited, index)?.let { element ->
                    val returnType = PhaenonErasure.nativeSignature(parent).returnType ?: PhaenonErasure.MIXED
                    val message = PhpBundle.message(
                        "inspection.message.return.type.declaration.must.be.compatible.with",
                        "${parentName(parent)}->${parent.name}()",
                        PhaenonErasure.present(returnType),
                    )
                    result.add(element to message)
                }
            }
            return result
        }

        /** The child's element to report when its parameters do not accept what the parent's accept, or null. */
        private fun declarationProblem(
            method: Method,
            own: PhaenonErasure.Signature,
            parent: Method,
            inherited: PhaenonErasure.Signature,
            index: PhpIndex,
        ): PsiElement? {
            val mine = method.parameters
            val theirs = parent.parameters
            val name = method.nameIdentifier ?: method
            val native = PhaenonErasure.nativeSignature(parent)
            for ((i, p) in theirs.withIndex()) {
                val k = if (i < mine.size) i else mine.indexOfLast { it.isVariadic }.takeIf { it >= 0 } ?: return name
                val m = mine[k]
                if (p.isVariadic && !m.isVariadic) return m
                if (isOptional(p) && !isOptional(m)) return m
                if (p.isPassByRef != m.isPassByRef) return m
                if (inherits(m, own.parameters[k], native.parameters[i])) continue
                if (!PhaenonErasure.isSubtype(inherited.parameters[i], own.parameters[k], index)) return m
            }
            for (k in theirs.size until mine.size) {
                if (!isOptional(mine[k])) return mine[k]
            }
            return null
        }

        /** The child's element to report when its return type is not one of the parent's, or null. */
        private fun returnProblem(method: Method, own: PhaenonErasure.Signature, parent: Method, inherited: PhaenonErasure.Signature, index: PhpIndex): PsiElement? {
            val theirs = inherited.returnType ?: return null
            val where = method.typeDeclaration ?: PhaenonErasedTypes.returnTypeLeaf(method) ?: method.nameIdentifier ?: method
            val mine = own.returnType ?: return where
            val native = PhaenonErasure.nativeSignature(parent).returnType
            if (native != null && PhaenonErasedTypes.returnType(method).isEmpty() && PhaenonErasure.sameNative(mine, native)) return null
            return if (PhaenonErasure.isSubtype(mine, theirs, index)) null else where
        }

        /**
         * A type the child writes natively and equal to the parent's erasure inherits the parent's
         * effective type, as PHPStan's implicit `@inheritDoc` and the checker read it
         * (`design/generics.md` §11.1): `normalize(object $value): mixed` under
         * `normalize(P $value): D` with `P of object, D of mixed` is the parent's signature.
         */
        private fun inherits(parameter: Parameter, mine: Set<PhaenonErasure.Atom>, parentNative: Set<PhaenonErasure.Atom>): Boolean =
            PhaenonErasedTypes.beforeName(parameter).isEmpty() && PhaenonErasure.sameNative(mine, parentNative)

        private fun isOptional(parameter: Parameter): Boolean = parameter.isOptional || parameter.isVariadic

        /** `AggregateRepository->save(aggregate: \App\Aggregate, [n: int = 0])`, with the types PHP sees. */
        private fun describe(parent: Method): String {
            val native = PhaenonErasure.nativeSignature(parent)
            val parameters = parent.parameters.mapIndexed { i, p ->
                val type = PhaenonErasure.present(native.parameters[i])
                val variadic = if (p.isVariadic) "..." else ""
                when {
                    p.isVariadic -> "$variadic${p.name}: $type"
                    p.isOptional -> "[${p.name}: $type = ${p.defaultValuePresentation ?: ""}]"
                    else -> "${p.name}: $type"
                }
            }
            return "${parentName(parent)}->${parent.name}(${parameters.joinToString(", ")})"
        }

        private fun parentName(parent: Method): String = (parent.containingClass as PhpClass?)?.name ?: ""
    }
}
