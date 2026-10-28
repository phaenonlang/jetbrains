package dev.phaenon.jetbrains

import com.intellij.openapi.application.PathManager
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Highlighting-only fallback: a TextMate grammar for `.phn` that wraps the stock `source.php`
 * grammar (PHP code without the HTML/open-tag outer layer).
 *
 * In PhpStorm this bundle is registered but never wins: the PHP file type claims `.phn`, and the
 * TextMate plugin only takes over extensions nobody else owns. It matters in IDEs where the PHP
 * plugin is missing or disabled, where it still gives readable `.phn` files. The bundle lives in
 * the `textmate/` directory of the plugin distribution, which is also a ready-made VS Code
 * extension layout.
 */
class PhaenonTextMateBundleProvider : TextMateBundleProvider {

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> {
        // The plugin is installed as <plugin dir>/lib/<jar>; the bundle sits in <plugin dir>/textmate.
        val jar = PathManager.getJarPathForClass(javaClass)?.let(Path::of) ?: return emptyList()
        val bundle = jar.parent?.parent?.resolve("textmate")?.resolve("phaenon") ?: return emptyList()
        return if (Files.isDirectory(bundle)) listOf(TextMateBundleProvider.PluginBundle("Phaenon", bundle)) else emptyList()
    }
}
