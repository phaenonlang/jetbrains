package dev.phaenon.jetbrains

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.util.ProcessingContext

/**
 * Gives every class name inside an erased construct a reference, so that `DomainEvent` in
 * `list<DomainEvent>` navigates, is found by find-usages, follows a rename and keeps its import
 * used — as it would in a `@param list<DomainEvent>` docblock.
 */
class PhaenonErasedReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PhaenonTokenTypes.ERASED), Provider)
    }

    private object Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
            PhaenonErasedNames.classNames(element).map { (range, name) -> PhaenonClassReference(element, range, name) }.toTypedArray()
    }
}

/**
 * A class name written in an erased construct. [qualified] is the name as written (`User`,
 * `Event\Created`, `\Closure`); the range covers its last segment, which is what a rename changes.
 */
class PhaenonClassReference(element: PsiElement, range: TextRange, private val qualified: String) :
    PsiPolyVariantReferenceBase<PsiElement>(element, range, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        PsiElementResolveResult.createResults(PhaenonErasedNames.resolve(qualified, element))

    override fun handleElementRename(newElementName: String): PsiElement {
        val text = element.text
        val range = rangeInElement
        val renamed = text.substring(0, range.startOffset) + newElementName + text.substring(range.endOffset)
        return (element as LeafElement).replaceWithText(renamed).psi
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
