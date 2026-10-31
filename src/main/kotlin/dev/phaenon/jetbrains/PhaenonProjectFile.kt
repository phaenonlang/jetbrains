package dev.phaenon.jetbrains

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads what the plugin needs from `phaenon.json`, the compiler's project file:
 *
 * ```json
 * {"roots": {"src": ".src", "tests": ".tests"}, "exclude": ["tests/Fixture"]}
 * ```
 *
 * Each root compiles into its output directory, a sibling with the same structure. The outputs
 * hold the compiled `.php`, which declares every class the `.phn` sources declare; the IDE must
 * not index them ([PhaenonExcludePolicy]).
 */
object PhaenonProjectFile {

    private val log = logger<PhaenonProjectFile>()

    /** The output directories named by the project file, relative to its directory; empty when it cannot be read. */
    @JvmStatic
    fun outputs(file: VirtualFile): List<String> {
        val text = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn("cannot read ${file.path}", e)
            return emptyList()
        }
        return try {
            val roots = JsonParser.parseString(text).asJsonObject.getAsJsonObject("roots") ?: return emptyList()
            roots.entrySet().mapNotNull { (_, output) -> output.takeIf { it.isJsonPrimitive }?.asString }
                .map { it.trim('/') }
                .filter { it.isNotEmpty() && it != "." && !it.startsWith("..") }
        } catch (e: Exception) {
            log.warn("${file.path} is not the project file the plugin expects", e)
            emptyList()
        }
    }
}
