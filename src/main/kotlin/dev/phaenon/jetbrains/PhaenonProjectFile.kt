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
 * not index them ([PhaenonExcludePolicy]). A source under a root compiles to the mirrored path in
 * its output (`src/Domain/Foo.phn` → `.src/Domain/Foo.php`), any other source beside itself — the
 * compiler's `outputPath` (`cmd/phn/project.go`), which [PhaenonLineMaps] follows to find the
 * compiled file of a source.
 */
object PhaenonProjectFile {

    private val log = logger<PhaenonProjectFile>()

    /** The output directories named by the project file, relative to its directory; empty when it cannot be read. */
    @JvmStatic
    fun outputs(file: VirtualFile): List<String> = roots(file).values.toList()

    /**
     * Each source root with its output directory, both relative to the project file's directory and
     * `/`-separated; empty when the file cannot be read. Pairs the compiler refuses (absolute,
     * outside the project, the same directory, not siblings) are left out.
     */
    @JvmStatic
    fun roots(file: VirtualFile): Map<String, String> {
        val text = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            log.warn("cannot read ${file.path}", e)
            return emptyMap()
        }
        return try {
            val roots = JsonParser.parseString(text).asJsonObject.getAsJsonObject("roots") ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((source, output) in roots.entrySet()) {
                val src = clean(source) ?: continue
                val dst = output.takeIf { it.isJsonPrimitive }?.asString?.let(::clean) ?: continue
                if (src == dst || src.substringBeforeLast('/', "") != dst.substringBeforeLast('/', "")) continue
                out[src] = dst
            }
            out
        } catch (e: Exception) {
            log.warn("${file.path} is not the project file the plugin expects", e)
            emptyMap()
        }
    }

    /** A directory inside the project, without `./` or trailing slashes; null for `.`, absolute paths and `..`. */
    private fun clean(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || path.startsWith("/") || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
