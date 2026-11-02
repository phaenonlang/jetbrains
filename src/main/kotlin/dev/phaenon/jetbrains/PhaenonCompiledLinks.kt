package dev.phaenon.jetbrains

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Links to compiled files in console text — a Run, Debug or Test console, the terminal, Analyze
 * Stack Trace — open the `.phn` at the forward-mapped line (design/line-map.md §4.2). The three
 * spellings PHP and its tools write:
 *
 * - `/app/.src/Foo.php:42` — PHPUnit, PHPStan's raw format (`:42:message`), php-cs-fixer, `path:line:column`
 * - `/app/.src/Foo.php(42)` — `Throwable::getTraceAsString()`
 * - `in /app/.src/Foo.php on line 42` — PHP's warnings and fatal errors
 *
 * A path is absolute, a `file://` URL, or relative to the project's base directory; only a
 * compiled file whose map leads to an existing source gets a link, every other `.php` is left to
 * PhpStorm's filters. The provider is first in order: where two filters link the same text the
 * platform keeps the first link (`CompositeFilter`), so this one replaces PhpStorm's link to the
 * compiled line, and PhpStorm's other links on the line stay.
 */
class PhaenonCompiledLinkFilter(private val project: Project) : Filter, DumbAware {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if (!line.contains(".php")) return null
        val start = entireLength - line.length
        var items: MutableList<Filter.ResultItem>? = null
        for (match in LINK.findAll(line)) {
            val path = match.groups["path"]!!.value
            val number = (match.groups["colon"] ?: match.groups["paren"] ?: match.groups["online"])!!.value.toIntOrNull() ?: continue
            val compiled = find(path) ?: continue
            val target = PhaenonLineMaps.forward(compiled, number - 1) ?: continue
            val item = Filter.ResultItem(start + match.range.first, start + match.range.last + 1, OpenFileHyperlinkInfo(project, target.file, target.line))
            (items ?: ArrayList<Filter.ResultItem>().also { items = it }).add(item)
        }
        return items?.let { Filter.Result(it) }
    }

    private fun find(path: String): VirtualFile? {
        val normal = path.replace('\\', '/')
        val absolute = normal.startsWith("/") || (normal.length > 2 && normal[1] == ':' && normal[2] == '/')
        val full = if (absolute) normal else project.basePath?.let { "$it/$normal" } ?: return null
        return LocalFileSystem.getInstance().findFileByPath(full)
    }

    private companion object {
        /** A `.php` path (an optional drive letter, no spaces, quotes, colons or parentheses) and its line in one of the three spellings. */
        val LINK = Regex(
            "(?:file://)?(?<path>(?:(?<![A-Za-z0-9])[A-Za-z]:)?[^\\s\"'<>():]*\\.php)" +
                "(?::(?<colon>\\d+)(?::\\d+)?|\\((?<paren>\\d+)\\)| on line (?<online>\\d+))",
        )
    }
}

/** Registers [PhaenonCompiledLinkFilter] for every console of a project. */
class PhaenonCompiledLinkFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(PhaenonCompiledLinkFilter(project))
}
