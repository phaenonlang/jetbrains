package dev.phaenon.jetbrains

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.documentation.phpdoc.lexer.PhpDocTokenTypes
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.editor.PhpEnterHandlerAdapter
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpClassFieldsList
import com.jetbrains.php.lang.psi.elements.PhpNamedElement

/**
 * Enter after the opening `/` `**` of a docblock in a `.phn` file: PhpStorm's stub without the tags a
 * declared type makes redundant.
 *
 * PhpStorm writes a `@param` tag for every parameter and a `@return` tag for the return type, and
 * types each from the declaration. In Phaenon the signature carries the types the docblock would
 * spell (the compiler writes the docblock types PHPStan needs into the PHP it emits), and a type
 * PHP cannot spell is erased before PhpStorm's parser, so to PhpStorm the declaration looked
 * untyped and the stub said `@return mixed` for `fun aggregateClass(): class<T>`.
 *
 * This delegate runs ahead of PhpStorm's PHP enter handler (`phpEnterHandler`), and only where
 * that handler would generate a stub (the caret right after the opening of a comment with nothing in
 * it yet): it hands the Enter to PhpStorm's registered delegate, which writes the stub as it always
 * does, then deletes the line of every `@param` of a parameter with a type, native or erased, the
 * `@return` of a function with a return type, and the `@var` of a typed property. Untyped
 * parameters keep PhpStorm's tags, as does whatever else PhpStorm adds, a `@throws` too unless the
 * signature's `throws` clause names its exception. A stub of one line or none is one line, whatever
 * it documents (design/functions.md §5): `/** <caret> */`, or `/** @param $raw */` with the caret
 * after the tag.
 *
 * Calling the registered delegate, rather than the class that writes the stub, keeps the plugin
 * working where PhpStorm moved that work: 2025.1 writes it in `PhpDocEnterHandler`, 2026.2 in a
 * backend subclass the adapter picks (`BackendPhpDocEnterHandler`).
 */
class PhaenonDocCommentEnterHandler : EnterHandlerDelegateAdapter() {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (!PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return EnterHandlerDelegate.Result.Continue
        val project = file.project
        val document = editor.document
        if (editor.isViewer || !document.isWritable) return EnterHandlerDelegate.Result.Continue
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val caret = editor.caretModel.offset
        if (caret == 0) return EnterHandlerDelegate.Result.Continue
        val elementAt = file.findElementAt(caret - 1) ?: return EnterHandlerDelegate.Result.Continue
        if (!opensAnEmptyDocComment(elementAt)) return EnterHandlerDelegate.Result.Continue

        val start = PsiTreeUtil.getParentOfType(elementAt, PhpDocComment::class.java, false)?.textRange?.startOffset
            ?: return EnterHandlerDelegate.Result.Continue
        val php = EnterHandlerDelegate.EP_NAME.extensionList.firstOrNull { it is PhpEnterHandlerAdapter }
            ?: return EnterHandlerDelegate.Result.Continue
        val result = php.preprocessEnter(file, editor, caretOffset, caretAdvance, dataContext, originalHandler)
        if (result != EnterHandlerDelegate.Result.Stop) return result
        PsiDocumentManager.getInstance(project).commitDocument(document)
        dropRedundantTags(file, editor, start)
        return EnterHandlerDelegate.Result.Stop
    }

    /** The caret right after the comment's opening, nothing after it: where PhpStorm generates a stub. */
    private fun opensAnEmptyDocComment(elementAt: PsiElement): Boolean {
        val comment = elementAt.parent as? PhpDocComment ?: return false
        val start = if (PsiUtilCore.getElementType(elementAt) === PhpDocTokenTypes.DOC_COMMENT_START) elementAt
        else PhpPsiUtil.getPrevSiblingIgnoreWhitespace(elementAt, true)?.takeIf { PsiUtilCore.getElementType(it) === PhpDocTokenTypes.DOC_COMMENT_START }
        if (start == null || start.parent !== comment) return false
        val next = PhpPsiUtil.getNextSiblingIgnoreWhitespace(start, true)
        return next == null || PsiUtilCore.getElementType(next) === PhpDocTokenTypes.DOC_IGNORED || next is PsiErrorElement
    }

    private fun dropRedundantTags(file: PsiFile, editor: Editor, start: Int) {
        val comment = PsiTreeUtil.findElementOfClassAtOffset(file, start, PhpDocComment::class.java, false) ?: return
        val redundant = ArrayList<PsiElement>()
        when (val owner = documented(comment)) {
            is Function -> {
                for (tag in comment.paramTags) {
                    val name = tag.varName?.removePrefix("$") ?: continue
                    val parameter = owner.parameters.firstOrNull { it.name == name } ?: continue
                    if (parameter.typeDeclaration != null || PhaenonErasedTypes.beforeName(parameter).isNotEmpty()) redundant.add(tag)
                }
                comment.returnTag?.let { if (owner.typeDeclaration != null || PhaenonErasedTypes.returnType(owner).isNotEmpty()) redundant.add(it) }
                // The signature's `throws` clause says what a `@throws` tag would (design/functions.md §4);
                // a tag for an exception the clause does not name stays.
                if (PhaenonErasedTypes.throwsClause(owner) != null) {
                    comment.getTagElementsByName("@throws").filterTo(redundant) { PhaenonThrows.declares(owner, it.type) }
                }
            }
            is Field -> comment.varTag?.let { if (owner.typeDeclaration != null || PhaenonErasedTypes.beforeName(owner).isNotEmpty()) redundant.add(it) }
        }
        val document = editor.document
        val caretLine = document.getLineNumber(editor.caretModel.offset)
        val lines = redundant.map { document.getLineNumber(it.textRange.startOffset) }.toSortedSet()
        for (line in lines.reversed()) document.deleteString(document.getLineStartOffset(line), document.getLineStartOffset(line + 1))
        PsiDocumentManager.getInstance(file.project).commitDocument(document)

        val range = PsiTreeUtil.findElementOfClassAtOffset(file, start, PhpDocComment::class.java, false)?.textRange ?: return
        val first = document.getLineNumber(range.startOffset)
        val last = document.getLineNumber(range.endOffset)
        val content = (first + 1 until last).map { document.getText(TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it))).trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
        if (content.size <= 1) {
            // A docblock of one line is one line (design/functions.md §5): `/** <caret> */`, or the one tag left.
            val line = content.singleOrNull()
            val text = if (line == null) "/**  */" else "/** $line */"
            document.replaceString(range.startOffset, range.endOffset, text)
            editor.caretModel.moveToOffset(range.startOffset + text.length - 3)
            return
        }
        if (redundant.isEmpty()) return
        // The caret stays on the line PhpStorm put it on, or ends the last line left above it.
        val removedAbove = lines.count { it < caretLine }
        val line = if (caretLine in lines) (caretLine - removedAbove - 1).coerceAtLeast(first + 1) else caretLine - removedAbove
        editor.caretModel.moveToOffset(document.getLineEndOffset(line.coerceIn(first + 1, last - 1)))
    }

    /** The declaration a docblock documents: the function or method after it, or the property of the field list after it. */
    private fun documented(comment: PhpDocComment): PhpNamedElement? {
        var next = PhpPsiUtil.getNextSiblingIgnoreWhitespace(comment, true)
        while (next != null && next !is Function && next !is PhpClassFieldsList && next !is Field) {
            // Attributes and modifiers come between a docblock and what it documents.
            if (next is PhpNamedElement) break
            next = PhpPsiUtil.getNextSiblingIgnoreWhitespace(next, true)
        }
        return when (next) {
            is Function -> next
            is Field -> next
            is PhpClassFieldsList -> next.fields.singleOrNull()
            else -> (comment.parent as? Function)
        }
    }
}
