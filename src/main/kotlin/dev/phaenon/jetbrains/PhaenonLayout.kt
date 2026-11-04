package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.FileIndentOptionsProvider
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor
import com.jetbrains.php.lang.PhpLanguage

/**
 * Reformat Code writes phn fmt's first layer (design/tooling.md §5.1): after PhpStorm's PHP
 * formatter has laid out the file with the project's PHP style and Phaenon's braces
 * ([PhaenonFormattingModelBuilder]), the project's `phn fmt --stdin` lays out what only Phaenon
 * knows: the spacing inside its own syntax (`<T of Item>`, `map<string, list<int>>`, `throws`,
 * `as?`), one-line docblocks, no blank line before the first line, spaces for tabs. Both share
 * their golden files, the compiler's `internal/format/testdata`.
 *
 * The layer runs right after the reformat, as an edit of its own: changing the document from
 * inside the formatter's post-processing breaks the caret bookkeeping of the reformat still under
 * way. Only a reformat of the whole file runs it: the layer is a file's, and a reformat of a range
 * (after completion, an inserted import, Implement Methods) must not change text outside it.
 * Without a `phn`, or on a text that does not parse, the file stays as PhpStorm formatted it.
 */
class PhaenonPostFormatProcessor : PostFormatProcessor {
    /** A reformat of an element: of the whole file, when the element is the file. */
    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (source is PsiFile) scheduleLayout(source, TextRange(0, source.textLength))
        return source
    }

    /** Reformat Code's own path: the whole file's range. */
    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        scheduleLayout(source, rangeToReformat)
        return rangeToReformat
    }

    private fun scheduleLayout(file: PsiFile, range: TextRange) {
        val vfile = file.viewProvider.virtualFile
        if (!PhaenonFiles.isPhaenonFile(vfile)) return
        if (range.startOffset > 0 || range.endOffset < file.textLength) return
        val project = file.project
        ApplicationManager.getApplication().invokeLater({ layout(project, file) }, ModalityState.defaultModalityState(), project.disposed)
    }

    private fun layout(project: Project, file: PsiFile) {
        if (!file.isValid) return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        documents.commitDocument(document)
        val text = document.text
        val laid = PhaenonCompiler.layout(project, file.viewProvider.virtualFile, text) ?: return
        if (laid == text) return
        WriteCommandAction.runWriteCommandAction(project, "Phaenon Layout", null, {
            if (document.text != text) return@runWriteCommandAction // edited meanwhile
            // Only what changed, so markers and the caret outside it stay where they were.
            replaceChanged(document, text, laid)
            documents.commitDocument(document)
        }, file)
    }
}

/**
 * The same layer before PhpStorm's formatter, on a reformat of the whole file: PhpStorm wraps a
 * line by its length, which Phaenon's own spacing changes (`map< string,list<int> >`), so it
 * should see the spacing as the layer writes it. Before formatting the document can be changed in
 * place; [PhaenonPostFormatProcessor] runs the layer again on what PhpStorm wrote.
 */
class PhaenonPreFormatProcessor : PreFormatProcessor {
    override fun process(element: ASTNode, range: TextRange): TextRange {
        val file = element.psi?.containingFile ?: return range
        val vfile = file.viewProvider.virtualFile
        if (!PhaenonFiles.isPhaenonFile(vfile)) return range
        val project = file.project
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return range
        if (range.startOffset > 0 || range.endOffset < document.textLength) return range
        documents.doPostponedOperationsAndUnblockDocument(document)
        val text = document.text
        val laid = PhaenonCompiler.layout(project, vfile, text) ?: return range
        if (laid == text) return range
        replaceChanged(document, text, laid)
        documents.commitDocument(document)
        return TextRange(0, document.textLength)
    }
}

/** Replaces the part of [document] that differs between [text], its text, and [laid]. */
internal fun replaceChanged(document: com.intellij.openapi.editor.Document, text: String, laid: String) {
    var prefix = 0
    val shortest = minOf(text.length, laid.length)
    while (prefix < shortest && text[prefix] == laid[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && text[text.length - 1 - suffix] == laid[laid.length - 1 - suffix]) suffix++
    document.replaceString(prefix, text.length - suffix, laid.substring(prefix, laid.length - suffix))
}

/**
 * A `.phn` is indented with spaces (D21, design/functions.md §5.5): a tab outside a string or a
 * comment's text does not compile. The project's PHP indentation with tabs turned off, for `.phn`
 * files only, overriding the PHP language's own options (as `.editorconfig` does), so Enter,
 * Reformat Code, generated code and the PHP formatter's blocks all write spaces.
 */
class PhaenonIndentOptionsProvider : FileIndentOptionsProvider() {
    override fun getIndentOptions(project: Project, settings: CodeStyleSettings, file: VirtualFile): CommonCodeStyleSettings.IndentOptions? {
        if (!PhaenonFiles.isPhaenonFile(file)) return null
        val php = settings.getLanguageIndentOptions(PhpLanguage.INSTANCE)
        if (!php.USE_TAB_CHARACTER) return null
        return (php.clone() as CommonCodeStyleSettings.IndentOptions).apply {
            USE_TAB_CHARACTER = false
            SMART_TABS = false
            // The formatter takes a PHP block's language options unless the file's override them.
            isOverrideLanguageOptions = true
        }
    }

    override fun useOnFullReformat(): Boolean = true
}
