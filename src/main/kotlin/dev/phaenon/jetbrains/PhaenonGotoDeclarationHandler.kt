package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocType
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.ConstantReference
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpReference

/**
 * Two corrections to "go to declaration".
 *
 * A class, function or constant declared in a `.phn` file is declared a second time by the `.php`
 * the compiler wrote from it, when that output is in the project (built beside the source, or
 * in an output directory the IDE was not told to exclude). PhpStorm's index holds both and
 * navigation lands on whichever comes first. Here a reference that resolves to a source
 * declaration and to its compiled copy goes to the source only, from `.phn` and `.php` files
 * alike. A hand-written `.php` declaring the same name stays a target: that is a real conflict.
 *
 * A docblock type naming a type parameter in scope, `@var T $x`, goes to the declaration of
 * `T` in the enclosing `<T of …>` list, which PhpStorm's own resolution cannot find.
 */
class PhaenonGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null) return null
        sourceDeclarations(sourceElement, offset)?.let { return it }
        return typeParameterInDocblock(sourceElement)?.let { arrayOf(it) }
    }

    /**
     * The targets of the reference at the caret with the compiled copies left out, or null when
     * the reference is not one this handler corrects: no `.phn` among the targets, or nothing
     * generated to drop.
     */
    private fun sourceDeclarations(sourceElement: PsiElement, offset: Int): Array<PsiElement>? {
        val reference: PhpReference = PsiTreeUtil.getParentOfType(
            sourceElement, ClassReference::class.java, ConstantReference::class.java, FunctionReference::class.java,
        ) ?: return null
        if (reference is MethodReference) return null
        if (reference is FunctionReference && reference.nameNode?.textRange?.contains(offset) != true) return null
        val targets = reference.multiResolve(false).mapNotNull { it.element }
        if (targets.none { PhaenonFiles.isPhaenonFile(it.containingFile?.viewProvider?.virtualFile) }) return null
        val sources = targets.filter { !PhaenonFiles.isGeneratedOutput(it.containingFile) }
        if (sources.size == targets.size || sources.isEmpty()) return null
        return sources.toTypedArray()
    }

    /** `@var T $x`: a docblock type naming a type parameter in scope goes to the parameter's declaration. */
    private fun typeParameterInDocblock(sourceElement: PsiElement): PsiElement? {
        if (!PhaenonErasedTypes.isPhaenon(sourceElement)) return null
        val docType = PsiTreeUtil.getParentOfType(sourceElement, PhpDocType::class.java, false) ?: return null
        return PhaenonDocTypeReferenceSearcher.docTypeReference(docType)?.resolve()
    }
}
