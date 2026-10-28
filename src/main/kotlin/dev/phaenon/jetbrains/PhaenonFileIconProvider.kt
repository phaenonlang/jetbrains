package dev.phaenon.jetbrains

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object PhaenonIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/phaenon.svg", PhaenonIcons::class.java)
}

/**
 * `.phn` files share the PHP file type (see [PhaenonLanguageSubstitutor] for why), so the icon is
 * overridden here instead of coming from a dedicated file type.
 */
class PhaenonFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (PhaenonFiles.isPhaenonFile(file)) PhaenonIcons.FILE else null
}
