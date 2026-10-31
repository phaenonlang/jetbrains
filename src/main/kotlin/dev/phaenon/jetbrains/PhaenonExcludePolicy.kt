package dev.phaenon.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VirtualFile

/**
 * Excludes the output directories of every `phaenon.json` from the project: the compiled `.php`
 * under them declares what the `.phn` sources declare, and the IDE should see a class once,
 * in its source. Project files are read at each content root and in each Composer package
 * under its `vendor` (a library shipped as Phaenon sources is built there on install).
 *
 * The directory index asks on project open and on every roots change;
 * [PhaenonProjectFileListener] triggers one when a project file changes.
 */
class PhaenonExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {

    override fun getExcludeUrlsForProject(): Array<String> {
        val urls = ArrayList<String>()
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            collect(root, urls)
            root.findChild("vendor")?.children?.forEach { vendorName ->
                if (vendorName.isDirectory) vendorName.children?.forEach { pkg -> if (pkg.isDirectory) collect(pkg, urls) }
            }
        }
        return urls.toTypedArray()
    }

    private fun collect(dir: VirtualFile, into: MutableList<String>) {
        val file = dir.findChild(PhaenonFiles.PROJECT_FILE)?.takeIf { !it.isDirectory } ?: return
        for (output in PhaenonProjectFile.outputs(file)) into.add(dir.url + "/" + output)
    }
}
