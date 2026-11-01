package dev.phaenon.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex

/**
 * Re-indexes the `.phn` files of a project once after a plugin update that changed their PSI.
 *
 * The stub trees of `.phn` files are versioned by PhpStorm's PHP file type, so the platform keeps
 * them across an update of this plugin even when [PhaenonFileElementType] now produces a different
 * tree; see [PhaenonFileElementType.PSI_VERSION]. On start-up this activity compares that version
 * with the one it stored in the project's workspace settings, and when they differ (or none is
 * stored) it asks the index for every `.phn` the project sees — content roots and libraries alike,
 * all of them indexed with the old PSI — then stores the current version, so the files are
 * re-indexed once per version and never again.
 *
 * The lookup waits for smart mode (the file-name index is unavailable in dumb mode and the
 * activity runs on a background coroutine, so waiting costs nothing); the re-index request is
 * what `FileBasedIndex` documents for "the content this file was indexed from is stale". The
 * internal constructor lets tests record the requests instead.
 */
class PhaenonReindexActivity internal constructor(private val requestReindex: (VirtualFile) -> Unit) : ProjectActivity {

    constructor() : this({ FileBasedIndex.getInstance().requestReindex(it) })

    override suspend fun execute(project: Project) {
        if (storedVersion(project) == PhaenonFileElementType.PSI_VERSION) return

        val files = smartReadAction(project) {
            FilenameIndex.getAllFilesByExt(project, PhaenonFiles.EXTENSION, GlobalSearchScope.allScope(project))
        }
        files.forEach(requestReindex)
        rememberCurrentVersion(project)
        log.info("re-indexed ${files.size} .phn file(s) of ${project.name} for PSI version ${PhaenonFileElementType.PSI_VERSION}")
    }

    companion object {

        private val log = logger<PhaenonReindexActivity>()

        /** The workspace property holding the [PhaenonFileElementType.PSI_VERSION] the project's `.phn` files were last indexed with. */
        const val VERSION_PROPERTY: String = "dev.phaenon.jetbrains.psiVersion"

        /** The version stored for the project, 0 when none is (a fresh project, or an install that predates the property). */
        @JvmStatic
        fun storedVersion(project: Project): Int = PropertiesComponent.getInstance(project).getInt(VERSION_PROPERTY, 0)

        @JvmStatic
        fun rememberCurrentVersion(project: Project) {
            PropertiesComponent.getInstance(project).setValue(VERSION_PROPERTY, PhaenonFileElementType.PSI_VERSION, 0)
        }
    }
}
