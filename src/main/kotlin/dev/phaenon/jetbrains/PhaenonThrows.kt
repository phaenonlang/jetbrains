package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.codeInsight.PhpScopeHolder
import com.jetbrains.php.lang.documentation.phpdoc.PhpDocUtil
import com.jetbrains.php.lang.inspections.PhpThrownExceptionsAnalyzer
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpThrowExpression
import com.jetbrains.php.lang.psi.elements.Try
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * A function's `throws A|B` clause (design/functions.md §4) as PhpStorm's exception analysis would
 * read the `@throws` tag the compiler writes for it.
 *
 * PhpStorm's analysis — "Unhandled X" at a `throw`, a call or a `new`, "PHPDoc comment doesn't
 * contain all the necessary @throws tags" on a docblock — reads `@throws` tags and offers no way to
 * feed it another source; the clause is an erased leaf to it ([PhaenonLexer]). The highlight filter
 * asks here whether such a finding is one the clauses answer, and drops it only then. What answers
 * an exception is what would answer it for PhpStorm in the compiled PHP:
 *
 * - the clause of the function the finding is in, and the clauses of the functions around it when
 *   that function is a closure, as PhpStorm reads a closure's exceptions against the enclosing
 *   function's `@throws` tags too;
 * - the clauses of the methods it overrides, when it has neither a docblock nor a clause of its own
 *   (or a docblock with `{@inheritDoc}`): its compiled copy has no docblock, and PhpStorm reads the
 *   parent's tags for a method without one;
 * - the `@throws` tags PhpStorm reads itself, for a site where it reports several exceptions at
 *   once, some of them documented.
 *
 * A clause naming a type parameter, bounded by a Throwable, may stand for any exception and answers
 * all of them. The exceptions are PhpStorm's own, from its public exception analysis
 * (`PhpThrownExceptionsAnalyzer`) asked as its inspections ask it: at a site, of the innermost
 * function that is not a closure, over the site's range; for a docblock, of the whole function,
 * less what its `@throws` tags (inherited ones included) and `@expectedException` document. The
 * inspections' own helpers are internal API. The public analysis of a site counts only the
 * catches within the site's range, where the inspection counts every one around it; the exceptions
 * the `try` statements around the site catch are taken off here ([caughtAround]). Where it is
 * still asked differently, it answers more, never less: everything thrown within a site's range,
 * where the inspection asks for the site alone; PHPUnit's expected exceptions left in. A finding the
 * clauses answer may then stay, but none they do not answer is dropped.
 */
object PhaenonThrows {

    /** Whether the clauses in effect at the "Unhandled …" finding on [leaf] name every exception PhpStorm reports there. */
    @JvmStatic
    fun answersUnhandled(leaf: PsiElement): Boolean {
        val site = PsiTreeUtil.getParentOfType(
            leaf, false, PhpThrowExpression::class.java, NewExpression::class.java, FunctionReference::class.java,
        ) ?: return false
        // The function the inspection reads the site against: the innermost one, closures included.
        val function = PhpPsiUtil.getParentOfClass(site, Function::class.java) ?: return false
        val holder = scopeHolderSkippingClosures(site) ?: return false
        val caught = caughtAround(site, holder)
        val index = PhpIndex.getInstance(site.project)
        val exceptions = PhpThrownExceptionsAnalyzer.getExceptionClasses(holder, site.textRange)
            .filterNot { !caught.isEmpty && caught.isConvertibleFrom(it, index) }
        if (exceptions.isEmpty()) return false
        val declared = declared(function, withDocTags = true) ?: return true
        return exceptions.all { declared.covers(it) }
    }

    /** Whether the clauses in effect for [function] name every exception its docblock is reported to lack. */
    @JvmStatic
    fun answersMissingTags(function: Function): Boolean {
        val missing = undocumented(function, PhpThrownExceptionsAnalyzer.getExceptionClasses(function))
        if (missing.isEmpty()) return false
        val declared = declared(function, withDocTags = false) ?: return true
        return missing.all { declared.covers(it) }
    }

    /** Whether [function]'s clause, or one it inherits, names [exception] or a parent of it: a `@throws` tag that says no more. */
    @JvmStatic
    fun declares(function: Function, exception: PhpType): Boolean {
        val declared = declared(function, withDocTags = false) ?: return true
        return declared.covers(exception)
    }

    /**
     * What the `catch` clauses of the `try` statements around [site], up to [holder], catch: the
     * public analysis counts only the catches within the site's range, the inspection every one
     * around it, so these come off the set as the inspection takes them off.
     */
    private fun caughtAround(site: PsiElement, holder: PhpScopeHolder): PhpType {
        val caught = PhpType()
        var parent = site.parent
        while (parent != null && parent !== holder) {
            if (parent is Try && PsiTreeUtil.isAncestor(parent.statement, site, false)) {
                for (clause in parent.catchClauses) for (type in clause.exceptionTypes) caught.add(type.fqn)
            }
            parent = parent.parent
        }
        return caught
    }

    /** The scope the analysis reads a site in, as the "Unhandled" inspection picks it: closures are skipped outwards. */
    private fun scopeHolderSkippingClosures(element: PsiElement): PhpScopeHolder? {
        val holder = PhpPsiUtil.getScopeHolder(element)
        return if (holder is Function && holder.isClosure) scopeHolderSkippingClosures(holder) else holder
    }

    /**
     * Of [thrown], the exceptions [function]'s docblock does not document, as the missing-`@throws`
     * inspection reads it: its `@throws` tags with those it inherits, and `@expectedException`.
     * Without a docblock or such a tag, all of them.
     */
    private fun undocumented(function: Function, thrown: Collection<PhpType>): List<PhpType> {
        val doc = function.docComment ?: return thrown.toList()
        val tags = PhpDocUtil.collectTagsConsideringInheritance(function, "@throws") + doc.getTagElementsByName("@expectedException")
        if (tags.isEmpty()) return thrown.toList()
        val documented = PhpType()
        for (tag in tags) documented.add(tag.type.global(function.project))
        val index = PhpIndex.getInstance(function.project)
        return thrown.filter { !documented.isConvertibleFrom(it, index) }
    }

    /** What the clauses in effect at [function] declare; null when one names a type parameter, which answers anything. */
    private fun declared(function: Function, withDocTags: Boolean): Declared? {
        val declared = Declared(PhpType(), PhpIndex.getInstance(function.project))
        if (!collect(function, declared, HashSet())) return null
        if (withDocTags) {
            var f: Function? = function
            while (f != null) {
                PhpDocUtil.collectTagsConsideringInheritance(f, "@throws").forEach { declared.types.add(it.type) }
                f = if (f.isClosure) PhpPsiUtil.getParentOfClass(f, Function::class.java) else null
            }
        }
        return declared
    }

    /** Adds the clauses in effect at [function] to [into]; false when one names a type parameter. */
    private fun collect(function: Function, into: Declared, seen: MutableSet<Function>): Boolean {
        if (!seen.add(function)) return true
        val names = PhaenonErasedTypes.throwsNames(function)
        if (names.isNotEmpty()) {
            val typeParameters = PhaenonErasedTypes.typeParametersInScope(function)
            val klass = PsiTreeUtil.getParentOfType(function, PhpClass::class.java)
            for (name in names) {
                when {
                    name in typeParameters -> return false
                    name.equals("self", true) || name.equals("static", true) -> klass?.let { into.types.add(it.fqn) }
                    name.equals("parent", true) -> klass?.superFQN?.let { into.types.add(it) }
                    else -> PhaenonErasedNames.resolve(name, function).forEach { into.types.add(it.fqn) }
                }
            }
        }
        if (function is Method) {
            val doc = function.docComment
            // The compiled method has no docblock, or one that inherits: PhpStorm reads the parent's.
            if (doc == null && names.isEmpty() || doc?.hasInheritDocTag() == true) {
                for (parent in superMethods(function)) if (!collect(parent, into, seen)) return false
            }
        }
        if (function.isClosure) {
            val outer = PhpPsiUtil.getParentOfClass(function, Function::class.java)
            if (outer != null && !collect(outer, into, seen)) return false
        }
        return true
    }

    /** The nearest declaration of [method] in each direct supertype of its class. */
    private fun superMethods(method: Method): List<Method> {
        val klass = method.containingClass ?: return emptyList()
        val name = method.name
        return (listOfNotNull(klass.superClass) + klass.implementedInterfaces)
            .mapNotNull { it.findMethodByName(name) }
            .filter { it !== method }
    }

    private class Declared(val types: PhpType, private val index: PhpIndex) {
        fun covers(exception: PhpType): Boolean = !types.isEmpty && types.isConvertibleFrom(exception, index)
    }
}
