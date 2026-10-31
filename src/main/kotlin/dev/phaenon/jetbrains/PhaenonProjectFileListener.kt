package dev.phaenon.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.project.RootsChangeRescanningInfo

/**
 * A `phaenon.json` written, changed or removed changes which directories [PhaenonExcludePolicy]
 * excludes, and the directory index only re-asks the policies on a roots change. This fires one.
 */
class PhaenonProjectFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed || events.none { it.path.endsWith("/" + PhaenonFiles.PROJECT_FILE) }) return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                WriteAction.run<RuntimeException> {
                    ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
                }
            }
        }, project.disposed)
    }
}
