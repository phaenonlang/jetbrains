package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Enter inside a `type` declaration of a `.phn` file, where PhpStorm sees one comment.
 *
 * The declaration is erased whole, its docblock with it ([PhaenonLexer]), so neither of the two
 * things Enter does there in PHP happens by itself. In the docblock, the new line is continued
 * with its ` * `, as PhpStorm continues a docblock it knows for one. Between the braces of a
 * shape just opened, `array{<caret>}`, the closing brace goes to a line of its own and the caret
 * to an indented one between, as between any pair of braces. Everywhere else in the declaration
 * Enter is the platform's, which keeps the line's indent.
 */
class PhaenonTypeDeclarationEnterHandler : EnterHandlerDelegateAdapter() {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (!PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return EnterHandlerDelegate.Result.Continue
        val offset = caretOffset.get()
        val leaf = declarationAt(file, offset) ?: return EnterHandlerDelegate.Result.Continue
        val text = editor.document.charsSequence
        val start = leaf.textRange.startOffset
        val inLeaf = offset - start
        val leafText = leaf.text
        val insertion = when {
            inDocblock(leafText, inLeaf) -> "\n" + indentOf(text, start) + " * "
            inLeaf in 1 until leafText.length && leafText[inLeaf - 1] == '{' && leafText[inLeaf] == '}' -> {
                val indent = indentOf(text, offset)
                val step = " ".repeat(CodeStyle.getIndentOptions(file).INDENT_SIZE)
                editor.document.insertString(offset, "\n$indent$step\n$indent")
                editor.caretModel.moveToOffset(offset + 1 + indent.length + step.length)
                return EnterHandlerDelegate.Result.Stop
            }
            else -> return EnterHandlerDelegate.Result.Continue
        }
        editor.document.insertString(offset, insertion)
        editor.caretModel.moveToOffset(offset + insertion.length)
        return EnterHandlerDelegate.Result.Stop
    }

    /** The erased `type` declaration the caret stands in, not at either of its ends. */
    private fun declarationAt(file: PsiFile, offset: Int): PsiElement? {
        val leaf = file.findElementAt(offset)?.takeIf { PhaenonTokenTypes.isErased(it) && it.textRange.startOffset < offset } ?: return null
        return leaf.takeIf { PhaenonType.alias(it.text) != null }
    }

    /**
     * Whether [at] stands inside the docblock a declaration's text begins with, one of several
     * lines: behind its opening and before the line that closes it.
     */
    private fun inDocblock(text: String, at: Int): Boolean {
        if (!text.startsWith("/**")) return false
        val end = text.indexOf("*/")
        if (end < 0 || at !in 3 until end || '\n' !in text.substring(0, end)) return false
        val closingLine = text.lastIndexOf('\n', end) + 1
        return at < closingLine || text.substring(closingLine, end).isNotBlank()
    }

    /** The blanks the line of [offset] begins with. */
    private fun indentOf(text: CharSequence, offset: Int): String {
        var line = offset
        while (line > 0 && text[line - 1] != '\n') line--
        var end = line
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(line, end).toString()
    }
}
