package dev.phaenon.jetbrains

import junit.framework.TestCase
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The line map against a real Xdebug, over DBGp, without the IDE: what PhpStorm's debugger would
 * see when it debugs `testData/linemap/project` (a compiled file laid out as design/line-map.md §2
 * says, with its map). Breakpoints go where [PhaenonDebugPathMapper] sends them (the reverse
 * targets), stops are shown where it maps them (forward), and steps are repeated as
 * [PhaenonSourceStepping] repeats them ([PhaenonStepping.repeat]).
 *
 * Runs with `PHAENON_XDEBUG=<path to xdebug.so>` (and `PHAENON_PHP`, default `php`); passes
 * trivially without it. Xdebug is loaded with `-n -d zend_extension=…` only.
 */
class PhaenonXdebugTest : TestCase() {

    private val xdebug = System.getProperty("phaenon.xdebug").orEmpty()
    private val php = System.getProperty("phaenon.php").orEmpty().ifEmpty { "php" }
    private val project = File("src/test/testData/linemap/project").absoluteFile
    private val compiled = File(project, ".src/Domain/Greeter.php")
    private val map by lazy { (PhaenonLineMap.read(compiled.readText()) as PhaenonLineMap.Parsed.Map).map }

    private fun enabled(): Boolean = xdebug.isNotEmpty().also { if (!it) println("PHAENON_XDEBUG is not set: skipped") }

    /** Each source line's breakpoint, sent to its reverse target, stops at a line that maps back to it. */
    fun testBreakpointsStopWhereTheSourceSaysAndMapBack() {
        if (!enabled()) return
        val lines = listOf(6, 11, 15)
        Dbgp(php, xdebug, File(project, "bin/run.php")).use { dbgp ->
            for (line in lines) dbgp.breakpoint(compiled, map.reverse(line)!!)
            val stops = dbgp.run("run")
            assertEquals(listOf("Greeter.phn:11", "Greeter.phn:6", "Greeter.phn:15"), stops.map { it.shown })
            // Xdebug moved the breakpoint on `fun greet(…) => …` from the signature line to the body's `return`: a hold of the same line.
            assertEquals(listOf(24, 14, 30), stops.map { it.compiledLine })
        }
    }

    /** Step Into from `$a = $this->greet($name);`: the void expression body's second stop (its closing brace) is stepped through. */
    fun testStepIntoShowsEachSourceLineOnce() {
        if (!enabled()) return
        Dbgp(php, xdebug, File(project, "bin/run.php")).use { dbgp ->
            dbgp.breakpoint(compiled, map.reverse(11)!!)
            val stops = dbgp.run("step_into")
            assertEquals(
                listOf("Greeter.phn:11", "Greeter.phn:6", "Greeter.phn:8", "Greeter.phn:12", "run.php:5", "Greeter.phn:15", "run.php:6", "run.php:6"),
                stops.filter { !it.repeated }.map { it.shown },
            )
            assertEquals(listOf(31), stops.filter { it.repeated }.map { it.compiledLine })
        }
    }

    fun testStepOverShowsEachSourceLineOnce() {
        if (!enabled()) return
        Dbgp(php, xdebug, File(project, "bin/run.php")).use { dbgp ->
            dbgp.breakpoint(compiled, map.reverse(15)!!)
            val stops = dbgp.run("step_over")
            // `echo $s, "\n";` stops twice in plain PHP too, on a run line: shown twice.
            assertEquals(listOf("Greeter.phn:15", "run.php:6", "run.php:6"), stops.filter { !it.repeated }.map { it.shown })
        }
    }

    /** A stop: where Xdebug is, where the IDE shows it, and whether the step that reached it was repeated. */
    class Stop(val frame: Any, val compiledLine: Int, val shown: String, val stop: PhaenonStepping.Stop, val repeated: Boolean)

    /** A minimal DBGp client: Xdebug connects to it, it sends commands and reads the replies. */
    private inner class Dbgp(php: String, xdebug: String, script: File) : AutoCloseable {

        private val server = ServerSocket(0).apply { soTimeout = 20_000 }
        private val process: Process = ProcessBuilder(
            php, "-n", "-d", "zend_extension=$xdebug", "-d", "xdebug.mode=debug", "-d", "xdebug.start_with_request=yes",
            "-d", "xdebug.client_host=127.0.0.1", "-d", "xdebug.client_port=${server.localPort}", script.path,
        ).directory(script.parentFile).redirectErrorStream(true).start()
        private val socket: Socket = server.accept().apply { soTimeout = 20_000 }
        private val input: InputStream = socket.getInputStream()
        private var transaction = 0

        init {
            assertTrue(message().contains("<init"))
        }

        /** One message: its length in digits, NUL, the XML, NUL. */
        private fun message(): String {
            val length = StringBuilder()
            while (true) {
                val c = input.read()
                check(c >= 0) { "Xdebug closed the connection" }
                if (c == 0) break
                length.append(c.toChar())
            }
            val bytes = input.readNBytes(length.toString().toInt())
            check(input.read() == 0)
            return String(bytes, Charsets.UTF_8)
        }

        fun send(command: String): String {
            val id = ++transaction
            val (name, args) = command.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            socket.getOutputStream().apply { write("$name -i $id $args".trim().toByteArray() + 0.toByte()); flush() }
            while (true) {
                val reply = message()
                if (reply.contains("transaction_id=\"$id\"")) return reply
            }
        }

        fun breakpoint(file: File, line: Int) {
            assertTrue(send("breakpoint_set -t line -f file://${file.path} -n $line").contains("id=\""))
        }

        /** Runs to the first breakpoint, then repeats [command] until the script ends, repeating steps as the IDE does. */
        fun run(command: String): List<Stop> {
            val step = when (command) {
                "step_over" -> PhaenonStepping.Step.OVER
                "step_into" -> PhaenonStepping.Step.INTO
                else -> null
            }
            val stops = ArrayList<Stop>()
            var reply = send("run")
            var previous: PhaenonStepping.Stop? = null
            var last: PhaenonStepping.Step? = null
            while (reply.contains("status=\"break\"") && stops.size < 100) {
                val stop = top()
                val repeated = PhaenonStepping.repeat(last, previous, stop.stop)
                stops.add(Stop(stop.frame, stop.compiledLine, stop.shown, stop.stop, repeated))
                previous = stop.stop
                last = step
                reply = send(command)
            }
            return stops
        }

        /** The top frame as PhpStorm's debugger presents it, through the line map. */
        private fun top(): Stop {
            val frames = Regex("<stack ([^>]*)>").findAll(send("stack_get")).map { m ->
                Regex("(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            }.toList()
            val top = frames.first()
            val path = top.getValue("filename").removePrefix("file://")
            val line = top.getValue("lineno").toInt()
            // PhpStackFrame's identity: depth from the bottom, file, function.
            val frame = Triple(frames.size - 1, path, top["where"])
            val file = File(path)
            val map = (PhaenonLineMap.read(file.readText()) as? PhaenonLineMap.Parsed.Map)?.map
            if (map == null) return Stop(frame, line, "${file.name}:$line", PhaenonStepping.Stop(frame, path, line - 1, false), false)
            val source = File(PhaenonLineMaps.resolve(file.parent, map.source)!!)
            val shown = map.forward(line)!!
            return Stop(frame, line, "${source.name}:$shown", PhaenonStepping.Stop(frame, source.path, shown - 1, map.isHold(line)), false)
        }

        override fun close() {
            runCatching { socket.close() }
            runCatching { server.close() }
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
