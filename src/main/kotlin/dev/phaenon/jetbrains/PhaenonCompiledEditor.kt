package dev.phaenon.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.UIUtil
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Opening a compiled file at a position opens its `.phn` at the forward-mapped line instead
 * (design/line-map.md §4.2), from wherever the position comes: a console link of PhpStorm's own
 * filters, PHPUnit's test tree and its failed line, inspection and quality-tool results, Find
 * Usages, Navigate → File `Foo.php:42`, the debugger's execution point without a mapping.
 *
 * Every such navigation reaches the opened file's editors through
 * `NavigatableFileEditor.navigateTo(descriptor)` (`FileEditorManagerImpl.navigateAndSelectEditor`).
 * So the compiled files whose map leads to a source get this provider's editor — PhpStorm's text
 * editor, wrapped, in place of the default one — and its [PhaenonCompiledTextEditor.navigateTo]
 * redirects. A compiled file opened without a position (the Project view) shows as itself, and a
 * navigation inside it (its editor has the focus) stays in it.
 */
class PhaenonCompiledEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = PhaenonLineMaps.isMappedOutput(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val inner = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return PhaenonCompiledTextEditor(project, file, inner)
    }

    override fun getEditorTypeId(): String = "phaenon-compiled"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** PhpStorm's text editor of a compiled file, but a navigation from elsewhere to a position in it goes to the source. */
class PhaenonCompiledTextEditor(
    private val project: Project,
    private val compiled: VirtualFile,
    private val inner: TextEditor,
) : TextEditor by inner {

    private val created = System.currentTimeMillis()
    private var navigated = false

    override fun getFile(): VirtualFile = compiled

    override fun navigateTo(navigatable: Navigatable) {
        val first = !navigated
        navigated = true
        val line = lineOf(navigatable)
        val target = line?.let { PhaenonLineMaps.forward(compiled, it) }
        inner.navigateTo(navigatable)
        if (target == null || isFocused()) return
        // Opened for this navigation: the compiled tab goes once the source is shown.
        val openedForIt = first && System.currentTimeMillis() - created < OPENED_FOR_IT_MS
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            PhaenonCompiledNavigation.openSource(project, target)
            if (openedForIt) FileEditorManager.getInstance(project).closeFile(compiled)
        }, project.disposed)
    }

    /** The 0-based line a navigation goes to, or null when it carries no position. */
    private fun lineOf(navigatable: Navigatable): Int? {
        val descriptor = navigatable as? OpenFileDescriptor ?: return null
        if (descriptor.file != compiled) return null
        if (descriptor.line >= 0) return descriptor.line
        if (descriptor.offset < 0) return null
        val document = inner.editor.document
        return document.getLineNumber(descriptor.offset.coerceAtMost(document.textLength))
    }

    /** Whether the developer is working in this editor: a navigation from here stays in the compiled file. */
    private fun isFocused(): Boolean {
        val owner = IdeFocusManager.getInstance(project).focusOwner ?: return false
        return SwingUtilities.isDescendingFrom(owner, inner.component) || UIUtil.isAncestor(inner.editor.contentComponent, owner)
    }

    private companion object {
        /** How soon after its creation a first navigation counts as the one that opened the editor. */
        const val OPENED_FOR_IT_MS = 2_000L
    }
}

/** Opening a source at a mapped line, shared by the editor redirect and the banner. */
object PhaenonCompiledNavigation {
    @JvmStatic
    fun openSource(project: Project, target: PhaenonLineMaps.Position) {
        OpenFileDescriptor(project, target.file, target.line, 0).navigate(true)
    }
}

/**
 * A banner on a compiled file: which source it was compiled from, a link to the source at the
 * caret's line, and a warning when the source has changed since (the map is as old as the build).
 */
class PhaenonCompiledNotificationProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val source = PhaenonLineMaps.sourceOf(file) ?: return null
        val shown = project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(source, it) } ?: source.path
        val stale = source.timeStamp > file.timeStamp
        return Function { editor ->
            EditorNotificationPanel(editor, if (stale) EditorNotificationPanel.Status.Warning else EditorNotificationPanel.Status.Info).apply {
                text = if (stale) "Compiled by phn from $shown, which has changed since: rebuild for the lines to match"
                else "Compiled by phn from $shown"
                createActionLabel("Open the source at this line") {
                    val line = (editor as? TextEditor)?.editor?.caretModel?.logicalPosition?.line ?: 0
                    val target = PhaenonLineMaps.forward(file, line) ?: PhaenonLineMaps.Position(source, 0)
                    PhaenonCompiledNavigation.openSource(project, target)
                }
            }
        }
    }
}
