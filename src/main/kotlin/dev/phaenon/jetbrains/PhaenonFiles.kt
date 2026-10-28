package dev.phaenon.jetbrains

import com.intellij.openapi.vfs.VirtualFile

/**
 * The one fact every part of the plugin shares: which files are Phaenon source.
 *
 * Kept free of PHP plugin imports on purpose. The pieces that run without the PHP plugin (file
 * icon, TextMate fallback) load this class, and a reference to a PHP class from here would make
 * them fail to load in IDEs where PHP support is absent or disabled.
 */
object PhaenonFiles {

    const val EXTENSION: String = "phn"

    const val PLUGIN_ID: String = "dev.phaenon.jetbrains"

    @JvmStatic
    fun isPhaenonFile(file: VirtualFile?): Boolean =
        file != null && EXTENSION.equals(file.extension, ignoreCase = true)
}
