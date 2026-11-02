package dev.phaenon.jetbrains

/**
 * The line map `phn build` writes as the last line of every compiled file (design/line-map.md §3):
 *
 * ```php
 * // phaenon-map v1 ../../src/Domain/Foo.phn 1-6=1 7:1 15-17=9 18:9
 * ```
 *
 * `O:S` is a run (output line `O` is source line `S`, and each following output line the next
 * source line, up to the next entry); `O-P=S` is a hold (output lines `O` to `P` are all source
 * line `S`: the lines the compiler wrote, or a source line spread over several). The last entry
 * extends past the end of the file. Line numbers here are 1-based, as in the map; callers working
 * with the platform's 0-based lines convert at their edge.
 *
 * Pure Kotlin, no platform types: the format has three readers (Go in `phn`, this one, PHP in
 * `phaenon/runtime`) and the compiler's `internal/linemap/testdata/vectors.json` is the reference
 * all of them are tested against (`src/test/resources/linemap/vectors.json`, a verbatim copy).
 */
class PhaenonLineMap private constructor(
    /** The source file relative to the compiled file's directory, `/`-separated, decoded. */
    val source: String,
    private val entries: List<Entry>,
) {

    /** A run when [holdEnd] is null, else a hold of output lines [output]..[holdEnd]. */
    private class Entry(val output: Int, val source: Int, val holdEnd: Int?) {
        val isHold: Boolean get() = holdEnd != null
    }

    /** The outcome of reading a line that may be a map. */
    sealed interface Parsed {
        data class Map(val map: PhaenonLineMap) : Parsed
        /** A map of a version this reader does not know: no mapping, as for a file without one. */
        data object Unsupported : Parsed
        /** A line that starts like a map but breaks the format: no mapping. */
        data object Invalid : Parsed
        /** Not a map line at all. */
        data object Absent : Parsed
    }

    /** The source line of output line [line] (forward lookup, for traces and the debugger's position). */
    fun forward(line: Int): Int? {
        val entry = decidingEntry(line) ?: return null
        return if (entry.isHold) entry.source else entry.source + (line - entry.output)
    }

    /** Whether output line [line] is one the compiler wrote or a continuation: a hold, never a breakpoint target. */
    fun isHold(line: Int): Boolean = decidingEntry(line)?.isHold ?: false

    /**
     * The output line a breakpoint on source line [line] goes to (reverse lookup): the first output
     * line of a run covering it, or, for a source line the compiler folded into another, the target
     * of the next source line that has one, as Xdebug resolves a breakpoint on a line without code.
     * Null when no later source line has a target either.
     */
    fun reverse(line: Int): Int? {
        if (line < 1) return null
        // The first source line at or after [line] that some run covers.
        var first = Int.MAX_VALUE
        forEachRun { _, start, end -> if (end >= line) first = minOf(first, maxOf(start, line)) }
        if (first == Int.MAX_VALUE) return null
        var target = Int.MAX_VALUE
        forEachRun { output, start, end -> if (first in start..end) target = minOf(target, output + (first - start)) }
        return target
    }

    /** Each run as (its first output line, the source lines it covers: first, last — open-ended for the last entry). */
    private inline fun forEachRun(action: (output: Int, start: Int, end: Int) -> Unit) {
        for ((i, entry) in entries.withIndex()) {
            if (entry.isHold) continue
            val next = entries.getOrNull(i + 1)
            val end = if (next == null) Int.MAX_VALUE else entry.source + (next.output - entry.output) - 1
            action(entry.output, entry.source, end)
        }
    }

    /** The entry with the largest output line not above [line]. */
    private fun decidingEntry(line: Int): Entry? {
        if (line < 1) return null
        var low = 0
        var high = entries.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (entries[mid].output <= line) low = mid else high = mid - 1
        }
        return entries[low].takeIf { it.output <= line }
    }

    override fun toString(): String = "PhaenonLineMap($source, ${entries.size} entries)"

    companion object {

        private const val MARKER = "// phaenon-map "

        /** The spelling after inline HTML: an open tag, then the comment. */
        private const val MARKER_AFTER_HTML = "<?php // phaenon-map "

        private const val VERSION = "v1"

        private val VERSION_TOKEN = Regex("v[0-9]+")

        /** Whether [line] is spelt as a map line, valid or not. */
        @JvmStatic
        fun looksLikeMap(line: CharSequence): Boolean = line.startsWith(MARKER) || line.startsWith(MARKER_AFTER_HTML)

        /** Reads one line (without its line break) as a map. */
        @JvmStatic
        fun parse(line: String): Parsed {
            val body = when {
                line.startsWith(MARKER) -> line.substring(MARKER.length)
                line.startsWith(MARKER_AFTER_HTML) -> line.substring(MARKER_AFTER_HTML.length)
                else -> return Parsed.Absent
            }
            // Tokens are separated by single spaces: an empty token is a doubled or trailing space.
            val tokens = body.split(' ')
            if (tokens.any { it.isEmpty() }) return Parsed.Invalid
            val version = tokens[0]
            if (version != VERSION) return if (VERSION_TOKEN.matches(version)) Parsed.Unsupported else Parsed.Invalid
            if (tokens.size < 3) return Parsed.Invalid
            val source = decodePath(tokens[1]) ?: return Parsed.Invalid
            val entries = ArrayList<Entry>(tokens.size - 2)
            for (token in tokens.subList(2, tokens.size)) entries.add(entry(token) ?: return Parsed.Invalid)
            if (!valid(entries)) return Parsed.Invalid
            return Parsed.Map(PhaenonLineMap(source, entries))
        }

        /**
         * The map of a compiled file's text: its last line, or, in a file that uses
         * `__halt_compiler()`, the line before that statement. Absent when the file has neither.
         */
        @JvmStatic
        fun read(text: CharSequence): Parsed {
            val last = lastLine(text)
            val parsed = parse(last)
            if (parsed != Parsed.Absent) return parsed
            return haltCompilerMapLine(text)?.let(::parse) ?: Parsed.Absent
        }

        private fun lastLine(text: CharSequence): String {
            var end = text.length
            while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == '\r')) end--
            var start = end
            while (start > 0 && text[start - 1] != '\n' && text[start - 1] != '\r') start--
            return text.subSequence(start, end).toString()
        }

        /** The map line just before a `__halt_compiler` statement, whose payload may follow in any bytes. */
        private fun haltCompilerMapLine(text: CharSequence): String? {
            val halt = Regex("(?im)^\\s*__halt_compiler\\s*\\(").find(text) ?: return null
            var end = halt.range.first
            while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == '\r')) end--
            var start = end
            while (start > 0 && text[start - 1] != '\n' && text[start - 1] != '\r') start--
            return text.subSequence(start, end).toString().takeIf(::looksLikeMap)
        }

        /** `%20` and `%25` are the only escapes the writer uses (a space and a percent sign). */
        private fun decodePath(token: String): String? {
            if (!token.contains('%')) return token
            val out = StringBuilder(token.length)
            var i = 0
            while (i < token.length) {
                val c = token[i]
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                when (token.substring(i + 1, minOf(token.length, i + 3))) {
                    "20" -> out.append(' ')
                    "25" -> out.append('%')
                    else -> return null
                }
                i += 3
            }
            return out.toString()
        }

        private fun entry(token: String): Entry? {
            val equals = token.indexOf('=')
            if (equals >= 0) {
                val dash = token.indexOf('-')
                if (dash < 0 || dash > equals) return null
                val output = number(token.substring(0, dash)) ?: return null
                val end = number(token.substring(dash + 1, equals)) ?: return null
                val source = number(token.substring(equals + 1)) ?: return null
                return if (end >= output) Entry(output, source, end) else null
            }
            val colon = token.indexOf(':')
            if (colon < 0) return null
            val output = number(token.substring(0, colon)) ?: return null
            val source = number(token.substring(colon + 1)) ?: return null
            return Entry(output, source, null)
        }

        /** A line number: decimal digits only, at least 1. */
        private fun number(text: String): Int? {
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            return text.toIntOrNull()?.takeIf { it >= 1 }
        }

        /** The first entry starts at output line 1; entries ascend and never overlap; a hold `O-P=S` is followed at `P+1`. */
        private fun valid(entries: List<Entry>): Boolean {
            if (entries.isEmpty() || entries[0].output != 1) return false
            for (i in 1 until entries.size) {
                val previous = entries[i - 1]
                val output = entries[i].output
                val ok = if (previous.isHold) output == previous.holdEnd!! + 1 else output > previous.output
                if (!ok) return false
            }
            return true
        }
    }
}
