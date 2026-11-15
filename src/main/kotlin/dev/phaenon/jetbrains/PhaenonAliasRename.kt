package dev.phaenon.jetbrains

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException

/**
 * Rename is not offered for a `type` alias, and says so.
 *
 * To PhpStorm an alias's name is three unrelated things: a class reference that resolves to
 * nothing in a signature, a docblock or a `use`, a word in a comment in its `type` declaration, and
 * a reference of the plugin's inside an erased construct (`list<Row>`), the only one of the three
 * that resolves to the alias ([PhaenonTypeAlias]). The platform's rename found that one, offered
 * its dialog there, and renamed the erased constructs alone: the declaration, the imports and
 * every signature kept the old name, in a program that compiled before.
 *
 * Until the plugin renames all of them, it renames none. [PhaenonAliasRenameVeto] takes the alias
 * away from the platform's handler; [PhaenonAliasRenameHandler] answers Rename wherever the caret
 * stands on an alias's name, in its declaration or in any use, with the reason;
 * [PhaenonAliasRenameProcessor] refuses a rename started on the element by other means, before a
 * single use is touched.
 */
object PhaenonAliasRename {

    fun message(name: String): String =
        "Rename is not supported for the type alias '$name': change the name in its type declaration and in the files that name it."

    /** The alias whose name the caret at [offset] stands on or right behind: in its declaration, or in a use of any kind. */
    fun aliasAt(file: PsiFile, offset: Int): PhaenonTypeAlias? {
        if (!PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return null
        for (at in intArrayOf(offset, offset - 1)) {
            val leaf = (if (at >= 0) file.findElementAt(at) else null) ?: continue
            named(leaf, at - leaf.textRange.startOffset)?.let { return it }
        }
        return null
    }

    private fun named(leaf: PsiElement, offsetInLeaf: Int): PhaenonTypeAlias? {
        if (!PhaenonTokenTypes.isErased(leaf)) return PhaenonAliases.namedAt(leaf).firstOrNull()
        PhaenonTypeAlias.of(leaf)?.takeIf { it.textRange.shiftLeft(leaf.textRange.startOffset).containsOffset(offsetInLeaf) }?.let { return it }
        return leaf.references.filter { it.rangeInElement.containsOffset(offsetInLeaf) }.firstNotNullOfOrNull { reference ->
            (reference as? PsiPolyVariantReference)?.multiResolve(false)?.firstNotNullOfOrNull { it.element as? PhaenonTypeAlias }
        }
    }
}

/** The platform's rename is not for an alias: it knows a third of the alias's uses. */
class PhaenonAliasRenameVeto : Condition<PsiElement> {
    override fun value(element: PsiElement?): Boolean = element is PhaenonTypeAlias
}

/** Rename on an alias's name, wherever it stands: the reason it is not done, in place of a dialog that would do a part. */
class PhaenonAliasRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = alias(dataContext) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val name = alias(dataContext)?.name ?: return
        CommonRefactoringUtil.showErrorHint(project, editor, PhaenonAliasRename.message(name), TITLE, null)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        val name = (elements.firstOrNull() as? PhaenonTypeAlias)?.name ?: alias(dataContext)?.name ?: return
        CommonRefactoringUtil.showErrorHint(project, CommonDataKeys.EDITOR.getData(dataContext), PhaenonAliasRename.message(name), TITLE, null)
    }

    private fun alias(dataContext: DataContext): PhaenonTypeAlias? {
        (CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? PhaenonTypeAlias)?.let { return it }
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        return PhaenonAliasRename.aliasAt(file, editor.caretModel.offset)
    }

    private companion object {
        const val TITLE = "Rename"
    }
}

/**
 * A rename of the alias's element that reached the processor without the handlers: no use is
 * looked for and none is changed, and the rename fails with the reason.
 */
class PhaenonAliasRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element is PhaenonTypeAlias

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> = emptyList()

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        throw IncorrectOperationException(PhaenonAliasRename.message((element as PhaenonTypeAlias).name))
    }
}
