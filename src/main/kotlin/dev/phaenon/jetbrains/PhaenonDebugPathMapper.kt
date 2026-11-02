package dev.phaenon.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.XSourcePositionWrapper
import com.jetbrains.php.debug.common.PhpLineBreakpointHandler
import com.jetbrains.php.debug.template.PhpTemplateDebugStateService
import com.jetbrains.php.debug.template.PhpTemplateLanguagePathMapper
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * Debugging `.phn` files through PhpStorm's template debugging, the mechanism behind Twig and
 * Blade: Xdebug runs the compiled `.php`, this mapper moves positions between it and the source
 * with the compiled file's line map (design/line-map.md §4.2).
 *
 * - A **frame** in a compiled file — the current stop, each entry of the Frames list, the editor's
 *   execution point — is built by PhpStorm's `PhpStackFrame`, which asks [isGeneratedFile] of its
 *   local file and takes [mapToTemplate]'s answer: the `.phn` at the forward-mapped line, as a
 *   [PhaenonMappedPosition] that remembers the compiled line ([PhaenonSourceStepping] reads it).
 * - A **breakpoint** in a `.phn` is registered by PhpStorm's `PhpLineBreakpointHandler`, which maps
 *   it with [mapToPhp] — to the compiled file at the reverse-mapped line — when the mapper found for
 *   the file answers [isTemplateFileType] for the file's type. An unmapped position (no compiled
 *   file, no valid map) leaves the breakpoint marked "No mapping to PHP line" by PhpStorm.
 * - A **hit** is recognised by PhpStorm by position: the stop's mapped position is compared with the
 *   breakpoints' files and lines, so conditions, log expressions and the suspend policy work on the
 *   `.phn` breakpoint as written.
 *
 * The one mismatch with the mechanism: a `.phn` is of the PHP file type, and [isTemplateFileType]
 * is asked of a file *type* — by the breakpoint handler, and by `PhpLineBreakpointType.canPutAt`,
 * where a yes would let a breakpoint go on any line of every `.php`. So the answer is yes only
 * for the breakpoint handler's own question about a `.phn`: the caller must be
 * `PhpLineBreakpointHandler.registerBreakpoint`, and the file the same thread just asked
 * [isGeneratedFile] about (which `getInstance` does right before) must be a `.phn`. A breakpoint
 * set in a compiled file is then registered as it is, and `.php` files keep PhpStorm's rules.
 *
 * `PhpTemplateLanguagePathMapper` is PHP plugin API marked internal; Twig and Blade, bundled with
 * PhpStorm, are its other implementations. The gate relies on how `registerBreakpoint` asks; if
 * that changes, `.phn` breakpoints stop being mapped (and are not hit) while `.php` is unaffected.
 */
class PhaenonDebugPathMapper : PhpTemplateLanguagePathMapper() {

    override fun isTemplateFileType(fileType: FileType): Boolean {
        val asked = lastAsked.get()
        lastAsked.remove()
        return fileType === PhpFileType.INSTANCE && asked != null && PhaenonFiles.isPhaenonFile(asked) && calledByBreakpointRegistration()
    }

    /** A `.phn` (the breakpoint side, mapped or not: a `.phn` never runs) or a compiled file whose map leads to its source. */
    override fun isGeneratedFile(file: VirtualFile, project: Project): Boolean {
        lastAsked.set(file)
        return PhaenonFiles.isPhaenonFile(file) || PhaenonLineMaps.isMappedOutput(file)
    }

    override fun mapToTemplate(phpPosition: XSourcePosition?, project: Project, remotePathMapper: PhpPathMapper): XSourcePosition? {
        if (phpPosition == null) return null
        val compiled = phpPosition.file
        val source = PhaenonLineMaps.forward(compiled, phpPosition.line) ?: return phpPosition
        val position = ApplicationManager.getApplication().runReadAction(Computable<XSourcePosition?> {
            XDebuggerUtil.getInstance().createPosition(source.file, source.line)
        }) ?: return phpPosition
        return PhaenonMappedPosition(position, compiled, phpPosition.line, PhaenonLineMaps.isHold(compiled, phpPosition.line))
    }

    override fun mapToPhp(templatePosition: XSourcePosition, project: Project, remotePathMapper: PhpPathMapper): XSourcePosition {
        if (!PhaenonFiles.isPhaenonFile(templatePosition.file)) return templatePosition
        val target = PhaenonLineMaps.reverse(templatePosition.file, templatePosition.line) ?: return templatePosition
        return ApplicationManager.getApplication().runReadAction(Computable<XSourcePosition?> {
            XDebuggerUtil.getInstance().createPosition(target.file, target.line)
        }) ?: templatePosition
    }

    /** No settings: the mapping comes from the compiled files themselves, not from a cache directory. */
    override fun getTemplateDebugInstance(project: Project): PhpTemplateDebugStateService? = null

    internal companion object {

        /** The file this thread last asked [isGeneratedFile] about: what `getInstance` was looking up. */
        private val lastAsked = ThreadLocal<VirtualFile?>()

        private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

        /** Whether `PhpLineBreakpointHandler.registerBreakpoint` (or an override of it) is asking, a few frames up. */
        fun calledByBreakpointRegistration(): Boolean = walker.walk { frames ->
            frames.limit(8).anyMatch { frame ->
                frame.methodName == "registerBreakpoint" && PhpLineBreakpointHandler::class.java.isAssignableFrom(frame.declaringClass)
            }
        }
    }
}

/**
 * A position in a `.phn` that PhpStorm's debugger reached in the compiled file: [compiledLine]
 * (0-based) of [compiled], which is a line the compiler wrote when [hold] is true.
 */
class PhaenonMappedPosition(
    source: XSourcePosition,
    val compiled: VirtualFile,
    val compiledLine: Int,
    val hold: Boolean,
) : XSourcePositionWrapper(source) {
    override fun toString(): String = "${file.name}:${line + 1} (${compiled.name}:${compiledLine + 1}${if (hold) ", hold" else ""})"
}
