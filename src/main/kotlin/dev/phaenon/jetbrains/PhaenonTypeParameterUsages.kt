package dev.phaenon.jetbrains

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.util.TextRange
import com.intellij.pom.PomDeclarationSearcher
import com.intellij.pom.PomTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Consumer
import com.intellij.util.Processor
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocType

/**
 * The declaration side of a type parameter. `T` in `class Box<T of Item>` is a declaration to
 * the platform: Cmd+click on it shows the usages instead of navigating, the way it does on a
 * class or method name. The usages are the erased constructs that mention `T`
 * ([PhaenonTypeParameterReference]) and the docblock types `@param T $x`, `@var T $x`, which
 * PhpStorm reads as class names and which [PhaenonDocTypeReferenceSearcher] adds.
 *
 * The parameter is reported as a declared target ([PhaenonTypeParameter] is a `PsiDeclaredTarget`):
 * the platform's default declaration provider makes the declaration of it, with the parameter
 * itself as the declared element, so find usages runs on [PhaenonTypeParameter].
 */
class PhaenonTypeParameterDeclarations : PomDeclarationSearcher() {

    override fun findDeclarationsAt(element: PsiElement, offsetInElement: Int, consumer: Consumer<in PomTarget>) {
        if (!PhaenonTokenTypes.isErased(element) || !PhaenonErasedTypes.isDeclarationList(element)) return
        PhaenonTypeText.parameters(element.text)
            .filter { (offset, name) -> offsetInElement in offset..offset + name.length }
            .forEach { (offset, name) -> consumer.consume(PhaenonTypeParameter(element, offset, name)) }
    }
}

/** Find-usages on a [PhaenonTypeParameter]: the platform's default handler over its references. */
class PhaenonTypeParameterFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = element is PhaenonTypeParameter

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        object : FindUsagesHandler(element) {}
}

/**
 * A docblock type that names a type parameter in scope — `@param T $x`, `@var T $item` — is
 * one of its usages. PhpStorm's own reference on the docblock type looks for a class, so the
 * reference offered here is Phaenon's, pointing at the declaration.
 */
class PhaenonDocTypeReferenceSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch as? PhaenonTypeParameter ?: return
        val scope = target.parent.parent ?: return
        for (docType in PsiTreeUtil.findChildrenOfType(scope, PhpDocType::class.java)) {
            val reference = docTypeReference(docType) ?: continue
            if (reference.resolve() == target && !consumer.process(reference)) return
        }
    }

    companion object {
        /** The reference to a type parameter a docblock type carries, when it names one in scope. */
        fun docTypeReference(docType: PhpDocType): PhaenonTypeParameterReference? {
            val name = docType.text.trim()
            if (name !in PhaenonErasedTypes.typeParametersInScope(docType)) return null
            return PhaenonTypeParameterReference(docType, TextRange(0, name.length), name)
        }
    }
}
