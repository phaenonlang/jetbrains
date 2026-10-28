package dev.phaenon.jetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Files

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
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PhaenonFiles.PLUGIN_ID))?.pluginPath ?: return emptyList()
        val bundle = pluginPath.resolve("textmate").resolve("phaenon")
        return if (Files.isDirectory(bundle)) listOf(TextMateBundleProvider.PluginBundle("Phaenon", bundle)) else emptyList()
    }
}
