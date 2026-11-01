package dev.phaenon.jetbrains

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
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
 * used — as it would in a `@param list<DomainEvent>` docblock. A type parameter used in one,
 * `T` in `id(): T`, refers to its declaration `<T of AggregateId>` instead.
 */
class PhaenonErasedReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PhaenonTokenTypes.ERASED), Provider)
    }

    private object Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val references = ArrayList<PsiReference>()
            PhaenonErasedNames.classNames(element).mapTo(references) { (range, name) -> PhaenonClassReference(element, range, name) }
            if (!PhaenonErasedTypes.isDeclarationList(element)) {
                PhaenonErasedNames.typeParameters(element).mapTo(references) { (range, name) -> PhaenonTypeParameterReference(element, range, name) }
            }
            return references.toTypedArray()
        }
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

    override fun handleElementRename(newElementName: String): PsiElement = renameIn(element, rangeInElement, newElementName)

    override fun getVariants(): Array<Any> = emptyArray()
}

/** A use of a type parameter in an erased construct, resolving to its declaration in the enclosing `<…>` list. */
class PhaenonTypeParameterReference(element: PsiElement, range: TextRange, private val name: String) :
    PsiReferenceBase<PsiElement>(element, range, true) {

    override fun resolve(): PsiElement? = PhaenonErasedTypes.typeParameterDeclaration(element, name)

    override fun handleElementRename(newElementName: String): PsiElement = renameIn(element, rangeInElement, newElementName)

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Replaces [range] of the erased leaf's text, which no PhpStorm manipulator knows how to edit. A
 * reference on another kind of element (a docblock type) is left as it is.
 */
private fun renameIn(element: PsiElement, range: TextRange, newName: String): PsiElement {
    val leaf = element as? LeafElement ?: return element
    val text = element.text
    val renamed = text.substring(0, range.startOffset) + newName + text.substring(range.endOffset)
    return leaf.replaceWithText(renamed).psi
}
