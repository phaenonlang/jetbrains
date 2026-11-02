package dev.phaenon.jetbrains

import com.google.gson.JsonParser
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * The PHP version a `.phn` file is compiled for, its project's target (design/php-versions.md §2):
 * `phaenon.json`'s `"php"` when it states one, otherwise the lowest version composer.json's
 * `require.php` admits, otherwise the floor, PHP 8.5 — the nearest of each file at or above the
 * source, as `phn` finds them. Outside 8.5…8.6 the target clamps to the nearest, as `phn check`
 * does after its diagnostic on the project file.
 *
 * What PHP 8.6 adds is valid Phaenon on an 8.6 target only, or lowered for 8.5 where the compiler
 * can; the editor asks this before dropping PhpStorm's findings on such code.
 */
object PhaenonTarget {

    /** A PHP release line, major.minor. */
    data class Version(val major: Int, val minor: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor)
        override fun toString(): String = "$major.$minor"
    }

    val PHP85 = Version(8, 5)
    val PHP86 = Version(8, 6)
    private val FLOOR = PHP85
    private val NEWEST = PHP86

    /** The target of the `.phn` file holding [element]; the floor outside a project. */
    @JvmStatic
    fun of(element: PsiElement): Version {
        val file = element.containingFile ?: return FLOOR
        return CachedValuesManager.getCachedValue(file) {
            val (version, dependencies) = compute(file)
            CachedValueProvider.Result.create(version, *(dependencies + VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS).toTypedArray())
        }
    }

    /** Whether the file holding [element] is compiled for PHP 8.6 or newer. */
    @JvmStatic
    fun isPhp86(element: PsiElement): Boolean = of(element) >= PHP86

    private fun compute(file: PsiFile): Pair<Version, List<Any>> {
        val start = (file.originalFile.viewProvider.virtualFile).parent ?: return FLOOR to emptyList()
        val project = nearest(start, PhaenonFiles.PROJECT_FILE)
        val composer = nearest(start, "composer.json")
        val dependencies = listOfNotNull<Any>(project, composer)
        project?.let { read(it, "php")?.let(::parse)?.let { v -> return clamp(v) to dependencies } }
        val requirePhp = composer?.let { readRequirePhp(it) } ?: return FLOOR to dependencies
        return clamp(lowestAdmitted(requirePhp) ?: FLOOR) to dependencies
    }

    private fun nearest(start: VirtualFile, name: String): VirtualFile? {
        var dir: VirtualFile? = start
        while (dir != null) {
            dir.findChild(name)?.takeIf { !it.isDirectory }?.let { return it }
            dir = dir.parent
        }
        return null
    }

    private fun json(file: VirtualFile) = try {
        JsonParser.parseString(String(file.contentsToByteArray(), Charsets.UTF_8)).asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun read(file: VirtualFile, key: String): String? =
        json(file)?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun readRequirePhp(file: VirtualFile): String? =
        json(file)?.getAsJsonObject("require")?.get("php")?.takeIf { it.isJsonPrimitive }?.asString

    private fun clamp(v: Version): Version = when {
        v < FLOOR -> FLOOR
        v > NEWEST -> NEWEST
        else -> v
    }

    /** A target as `phaenon.json` writes it, `"8.6"`. */
    fun parse(text: String): Version? {
        val m = Regex("^(\\d+)\\.(\\d+)$").matchEntire(text.trim()) ?: return null
        return Version(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    // ------------------------------------------------- Composer constraints, as composer/semver reads them

    /**
     * The major.minor of the lowest version a Composer constraint admits — `>=8.5`, `^8.5`,
     * `~8.5.0`, `8.5.*`, `^8.5 || ^9.0` all give 8.5 — or null when it admits every version or
     * cannot be read. The compiler's `phpversion.LowestAdmitted`, ported: alternatives joined by
     * `||` or `|`, constraints within one by `,` or spaces, hyphen ranges, wildcards, `^`, `~`, a
     * `v` prefix, pre-release suffixes and `@stability` flags. An alternative admitting nothing is
     * ignored.
     */
    fun lowestAdmitted(constraint: String): Version? {
        val text = constraint.trim()
        if (text.isEmpty()) return null
        var lowest: Point? = null
        for (alternative in text.split(OR_SPLIT)) {
            val interval = parseAnd(alternative.trim()) ?: return null
            if (interval.isEmpty()) continue
            val lo = interval.lo ?: return null
            if (lowest == null || less(lo.v, lowest.v)) lowest = lo
        }
        return lowest?.let { Version(it.v[0], it.v[1]) }
    }

    private class Point(val v: IntArray, val inclusive: Boolean)

    private class Interval(val lo: Point?, val hi: Point?) {
        fun isEmpty(): Boolean {
            if (lo == null || hi == null) return false
            if (less(hi.v, lo.v)) return true
            return lo.v.contentEquals(hi.v) && !(lo.inclusive && hi.inclusive)
        }

        fun intersect(o: Interval): Interval {
            var l = lo
            var h = hi
            if (o.lo != null && (l == null || less(l.v, o.lo.v) || l.v.contentEquals(o.lo.v) && !o.lo.inclusive)) l = o.lo
            if (o.hi != null && (h == null || less(o.hi.v, h.v) || h.v.contentEquals(o.hi.v) && !o.hi.inclusive)) h = o.hi
            return Interval(l, h)
        }
    }

    private fun less(a: IntArray, b: IntArray): Boolean {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    private val OR_SPLIT = Regex("\\s*\\|\\|?\\s*")
    private val HYPHEN_RANGE = Regex("^(\\S+)\\s+-\\s+(\\S+)$")
    private val OPERATOR_GAP = Regex("(>=|<=|<>|!=|==|[<>=^~])\\s+")
    private val STABILITY = Regex("(?i)@(stable|rc|beta|alpha|dev)$")
    private val ATOM = Regex(
        "^(>=|<=|<>|!=|==|[<>=^~])?v?(\\d+|[*xX])(?:\\.(\\d+|[*xX]))?(?:\\.(\\d+|[*xX]))?(?:\\.(\\d+|[*xX]))?" +
            "(?:[-.+]?(?i:dev|alpha|a|beta|b|rc|patch|pl|p)[.-]?\\d*)?(?:\\+\\S*)?$",
    )

    private fun parseAnd(input: String): Interval? {
        var s = STABILITY.replace(input, "")
        HYPHEN_RANGE.matchEntire(s)?.let { m ->
            val lo = parseAtom(">=" + m.groupValues[1]) ?: return null
            val hi = hyphenUpper(m.groupValues[2]) ?: return null
            return lo.intersect(hi)
        }
        s = OPERATOR_GAP.replace(s, "$1")
        var interval = Interval(null, null)
        for (part in s.split(',', ' ', '\t').filter { it.isNotEmpty() }) {
            interval = interval.intersect(parseAtom(STABILITY.replace(part, "")) ?: return null)
        }
        return interval
    }

    private fun hyphenUpper(s: String): Interval? {
        val m = ATOM.matchEntire(s) ?: return null
        if (m.groupValues[1].isNotEmpty()) return null
        val (v, n, _) = components(m)
        return if (n >= 3) Interval(null, Point(v, true)) else Interval(null, Point(bump(v, n - 1), false))
    }

    private data class Components(val v: IntArray, val n: Int, val wildcard: Boolean)

    private fun components(m: MatchResult): Components {
        val v = IntArray(3)
        var n = 0
        for (i in 0 until 3) {
            val c = m.groupValues[2 + i]
            if (c.isEmpty()) break
            if (c == "*" || c == "x" || c == "X") return Components(v, n, true)
            v[i] = c.toInt()
            n++
        }
        return Components(v, n, false)
    }

    private fun bump(v: IntArray, at: Int): IntArray {
        val i = at.coerceAtLeast(0)
        val r = v.copyOf()
        r[i]++
        for (j in i + 1 until 3) r[j] = 0
        return r
    }

    private fun parseAtom(s: String): Interval? {
        val m = ATOM.matchEntire(s) ?: return null
        val op = m.groupValues[1]
        val (v, n, wildcard) = components(m)
        fun at(inclusive: Boolean) = Point(v, inclusive)
        if (wildcard) {
            if (n == 0) return Interval(null, null)
            if (op == "" || op == "=" || op == "==") return Interval(at(true), Point(bump(v, n - 1), false))
        }
        return when (op) {
            "", "=", "==" -> Interval(at(true), at(true))
            ">=" -> Interval(at(true), null)
            ">" -> Interval(at(false), null)
            "<=" -> Interval(null, at(true))
            "<" -> Interval(null, at(false))
            "!=", "<>" -> Interval(null, null)
            "^" -> {
                var i = 0
                while (i < n - 1 && v[i] == 0) i++
                Interval(at(true), Point(bump(v, i), false))
            }
            "~" -> Interval(at(true), Point(bump(v, if (n == 1) 0 else n - 2), false))
            else -> null
        }
    }
}
