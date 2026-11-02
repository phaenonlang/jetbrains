package dev.phaenon.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The line maps of compiled files, read once per file version, and the two directions between a
 * compiled file and its source.
 *
 * A map is read from what is on disk when the file is local — the bytes PHP runs, even before the
 * IDE has refreshed its view of a tree `phn build` just rewrote — and from the virtual file
 * otherwise. Each read is cached under the file's disk modification time and size (or its
 * modification stamp), so a rebuilt file is read again on its next use and an unchanged one never.
 *
 * Positions here are the platform's: 0-based lines. The map's own numbers are 1-based
 * ([PhaenonLineMap]).
 */
object PhaenonLineMaps {

    /** A line in a file, 0-based. */
    data class Position(val file: VirtualFile, val line: Int)

    private class Cached(val stamp: Long, val size: Long, val map: PhaenonLineMap?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** The valid map of [compiled], or null: not a `.php`, no map, a malformed one, or a version this reader does not know. */
    @JvmStatic
    fun of(compiled: VirtualFile): PhaenonLineMap? {
        if (compiled.isDirectory || !"php".equals(compiled.extension, ignoreCase = true)) return null
        val disk = diskFile(compiled)
        val stamp = disk?.lastModified() ?: compiled.modificationStamp
        val size = disk?.length() ?: compiled.length
        cache[compiled.url]?.takeIf { it.stamp == stamp && it.size == size }?.let { return it.map }
        val map = read(compiled, disk)
        cache[compiled.url] = Cached(stamp, size, map)
        return map
    }

    private fun read(compiled: VirtualFile, disk: File?): PhaenonLineMap? {
        val text = try {
            if (disk != null) disk.readText(Charsets.UTF_8) else String(compiled.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return (PhaenonLineMap.read(text) as? PhaenonLineMap.Parsed.Map)?.map
    }

    private fun diskFile(file: VirtualFile): File? =
        if (file.isInLocalFileSystem) File(file.path).takeIf { it.isFile } else null

    /** The source [compiled]'s map names, resolved lexically against the compiled file's directory; null when it does not exist. */
    @JvmStatic
    fun sourceOf(compiled: VirtualFile): VirtualFile? {
        val map = of(compiled) ?: return null
        val dir = compiled.parent ?: return null
        val path = resolve(dir.path, map.source) ?: return null
        return find(dir, path)?.takeIf { !it.isDirectory && PhaenonFiles.isPhaenonFile(it) }
    }

    /**
     * The compiled file of [source]: the mirrored path in its root's output directory when the
     * nearest `phaenon.json` names one, else `Foo.php` beside `Foo.phn` — accepted only when that
     * file's map names [source] back.
     */
    @JvmStatic
    fun compiledOf(source: VirtualFile): VirtualFile? {
        if (!PhaenonFiles.isPhaenonFile(source)) return null
        for (path in compiledCandidates(source)) {
            val compiled = find(source, path) ?: continue
            if (sourceOf(compiled) == source) return compiled
        }
        return null
    }

    /**
     * [path] in the file system of [near]. A local file the IDE has not seen yet (a tree `phn build`
     * just wrote) is refreshed into view, except under a read lock, where a refresh may not run.
     */
    private fun find(near: VirtualFile, path: String): VirtualFile? {
        near.fileSystem.findFileByPath(path)?.let { return it }
        if (!near.isInLocalFileSystem || ApplicationManager.getApplication().isReadAccessAllowed || !File(path).exists()) return null
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
    }

    private fun compiledCandidates(source: VirtualFile): List<String> {
        val beside = source.path.removeSuffix("." + source.extension) + ".php"
        var dir = source.parent
        while (dir != null) {
            val projectFile = dir.findChild(PhaenonFiles.PROJECT_FILE)?.takeIf { !it.isDirectory }
            if (projectFile != null) {
                val relative = source.path.removePrefix(dir.path + "/")
                for ((root, output) in PhaenonProjectFile.roots(projectFile)) {
                    if (!relative.startsWith("$root/")) continue
                    val mirrored = output + "/" + relative.removePrefix("$root/")
                    return listOf(dir.path + "/" + mirrored.removeSuffix("." + source.extension) + ".php", beside)
                }
                return listOf(beside)
            }
            dir = dir.parent
        }
        return listOf(beside)
    }

    /** [line] (0-based) of [compiled] as a position in its source, or null when the file has no usable map. */
    @JvmStatic
    fun forward(compiled: VirtualFile, line: Int): Position? {
        val map = of(compiled) ?: return null
        val source = sourceOf(compiled) ?: return null
        val mapped = map.forward(line + 1) ?: return null
        return Position(source, mapped - 1)
    }

    /** [line] (0-based) of [source] as the position a breakpoint goes to in its compiled file, or null. */
    @JvmStatic
    fun reverse(source: VirtualFile, line: Int): Position? {
        val compiled = compiledOf(source) ?: return null
        val mapped = of(compiled)?.reverse(line + 1) ?: return null
        return Position(compiled, mapped - 1)
    }

    /** Whether [line] (0-based) of [compiled] is a line the compiler wrote or a continuation (a hold). */
    @JvmStatic
    fun isHold(compiled: VirtualFile, line: Int): Boolean = of(compiled)?.isHold(line + 1) ?: false

    /** Whether [file] is a compiled file whose map leads to an existing source. */
    @JvmStatic
    fun isMappedOutput(file: VirtualFile): Boolean = sourceOf(file) != null

    /** [relative] (`/`-separated) against the directory [base], without touching the file system; null when it climbs above the root. */
    @JvmStatic
    fun resolve(base: String, relative: String): String? {
        val parts = if (relative.startsWith("/")) ArrayList() else base.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in relative.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        val prefix = if (base.startsWith("/") || relative.startsWith("/")) "/" else ""
        return prefix + parts.joinToString("/")
    }
}
