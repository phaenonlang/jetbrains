package dev.phaenon.jetbrains

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.jetbrains.php.debug.common.PhpDebugProcess
import com.jetbrains.php.debug.common.PhpLineBreakpointHandler
import com.jetbrains.php.debug.common.PhpLineBreakpointType
import com.jetbrains.php.debug.template.PhpTemplateLanguagePathMapper
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * PhpStorm's template debugging with the line map: which files the mapper claims, both mappings
 * on the hand-compiled `testData/linemap/project` (laid out as design/line-map.md §2 says), and the
 * gate that keeps `.php` files' breakpoint rules PhpStorm's.
 */
class PhaenonDebugTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private lateinit var source: VirtualFile
    private lateinit var compiled: VirtualFile
    private lateinit var plain: VirtualFile

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("linemap/project", "app")
        source = myFixture.findFileInTempDir("app/src/Domain/Greeter.phn")
        compiled = myFixture.findFileInTempDir("app/.src/Domain/Greeter.php")
        plain = myFixture.findFileInTempDir("app/bin/run.php")
    }

    private fun position(file: VirtualFile, line: Int): XSourcePosition = XDebuggerUtil.getInstance().createPosition(file, line)!!

    private val mapper: PhaenonDebugPathMapper get() = PhpTemplateLanguagePathMapper.EP_NAME.findExtensionOrFail(PhaenonDebugPathMapper::class.java)

    private val pathMapper: PhpPathMapper get() = PhpPathMapper.createDefaultMapper()

    fun testTheMapperClaimsSourcesAndMappedOutputsOnly() {
        assertInstanceOf(PhpTemplateLanguagePathMapper.getInstance(source, project), PhaenonDebugPathMapper::class.java)
        assertInstanceOf(PhpTemplateLanguagePathMapper.getInstance(compiled, project), PhaenonDebugPathMapper::class.java)
        assertNull(PhpTemplateLanguagePathMapper.getInstance(plain, project))
        assertNull(PhpTemplateLanguagePathMapper.getInstance(myFixture.findFileInTempDir("app/.src/Domain/Stale.php"), project))
    }

    /** A stop in the compiled file shows in the `.phn`: the forward-mapped line, remembering the compiled one. */
    fun testFramesMapToTheSource() {
        // `return 'Hello, ' . $this->upper($name) . '!';` — compiled line 14, a hold of source line 6.
        val body = mapper.mapToTemplate(position(compiled, 13), project, pathMapper)
        assertInstanceOf(body, PhaenonMappedPosition::class.java)
        body as PhaenonMappedPosition
        assertEquals(source, body.file)
        assertEquals(5, body.line)
        assertEquals(compiled, body.compiled)
        assertEquals(13, body.compiledLine)
        assertTrue(body.hold)
        // `$a = $this->greet($name);` — compiled line 24, source line 11, a run.
        val statement = mapper.mapToTemplate(position(compiled, 23), project, pathMapper) as PhaenonMappedPosition
        assertEquals(10, statement.line)
        assertFalse(statement.hold)
        // Plain PHP and a `.phn` position stay as they are.
        val php = position(plain, 3)
        assertSame(php, mapper.mapToTemplate(php, project, pathMapper))
        val phn = position(source, 3)
        assertSame(phn, mapper.mapToTemplate(phn, project, pathMapper))
    }

    /** A breakpoint in the `.phn` goes to the compiled file at the reverse-mapped line; without a compiled file it stays (PhpStorm reports it unmapped). */
    fun testBreakpointsMapToTheCompiledFile() {
        val greet = mapper.mapToPhp(position(source, 5), project, pathMapper)
        assertEquals(compiled, greet.file)
        assertEquals(11, greet.line)
        val log = mapper.mapToPhp(position(source, 14), project, pathMapper)
        assertEquals(27, log.line)
        val lonely = myFixture.addFileToProject("app/src/Domain/Lonely.phn", "namespace App\\Domain;\n\nfinal class Lonely\n{\n}\n").virtualFile
        val unmapped = position(lonely, 2)
        assertSame(unmapped, mapper.mapToPhp(unmapped, project, pathMapper))
        val php = position(plain, 3)
        assertSame(php, mapper.mapToPhp(php, project, pathMapper))
    }

    /** Asked from anywhere but the breakpoint handler, the PHP file type is no template type: `.php` keeps PhpStorm's line rules. */
    fun testTheFileTypeQuestionIsNoOutsideBreakpointRegistration() {
        mapper.isGeneratedFile(source, project)
        assertFalse(mapper.isTemplateFileType(PhpFileType.INSTANCE))
        assertFalse(PhpTemplateLanguagePathMapper.isTemplateFile(plain))
        // A breakpoint still cannot go on a blank line of a `.php`.
        val blank = myFixture.addFileToProject("app/bin/blank.php", "<?php\n\n\necho 1;\n").virtualFile
        assertFalse(PhpLineBreakpointType.getInstance().canPutAt(blank, 1, project))
        assertTrue(PhpLineBreakpointType.getInstance().canPutAt(blank, 3, project))
    }

    /**
     * PhpStorm's `PhpLineBreakpointHandler.registerBreakpoint` asks `getInstance(file)` and then
     * `isTemplateFileType(file.fileType)`; the probe below asks the same from a method of that name
     * on a handler. For a `.phn` the answer is yes, for a compiled or plain `.php` no.
     */
    fun testTheBreakpointHandlerGetsTheSourceMapped() {
        val handler = Probe.create(project)
        assertTrue(handler.registerBreakpoint(source))
        assertFalse(handler.registerBreakpoint(compiled))
        assertFalse(handler.registerBreakpoint(plain))
    }

    /** A breakpoint handler that runs only the two questions `registerBreakpoint` asks. Created without its constructor: it needs no process. */
    class Probe private constructor(process: PhpDebugProcess<*>) : PhpLineBreakpointHandler<PhpDebugProcess<*>>(process) {

        private var project: com.intellij.openapi.project.Project? = null

        fun registerBreakpoint(file: VirtualFile): Boolean {
            val mapper = PhpTemplateLanguagePathMapper.getInstance(file, project!!) ?: return false
            return mapper.isTemplateFileType(file.fileType)
        }

        companion object {
            fun create(project: com.intellij.openapi.project.Project): Probe {
                val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val probe = (field.get(null) as sun.misc.Unsafe).allocateInstance(Probe::class.java) as Probe
                probe.project = project
                return probe
            }
        }
    }
}

/** The decision to repeat a step, on its own. */
class PhaenonSteppingTest : junit.framework.TestCase() {

    private val frame = Triple(1, "/app/.src/Greeter.php", "App\\Greeter->log")

    private fun stop(line: Int, hold: Boolean, frame: Any = this.frame, file: String = "file:///app/src/Greeter.phn") =
        PhaenonStepping.Stop(frame, file, line, hold)

    fun testAStepThatStaysOnASourceLineThroughAHoldIsRepeated() {
        assertTrue(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, stop(14, true), stop(14, true)))
        assertTrue(PhaenonStepping.repeat(PhaenonStepping.Step.INTO, stop(14, false), stop(14, true)))
    }

    fun testEverythingElseStops() {
        // A run line (plain PHP the source wrote, a loop on one line) is shown again.
        assertFalse(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, stop(14, false), stop(14, false)))
        // Another line, another frame, another file, no previous stop.
        assertFalse(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, stop(13, true), stop(14, true)))
        assertFalse(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, stop(14, true, frame = "other"), stop(14, true)))
        assertFalse(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, stop(14, true, file = "file:///b.phn"), stop(14, true)))
        assertFalse(PhaenonStepping.repeat(PhaenonStepping.Step.OVER, null, stop(14, true)))
        // A resume that is no step: Resume, Step Out, Run to Cursor — a breakpoint hit again in a loop stops.
        assertFalse(PhaenonStepping.repeat(null, stop(14, true), stop(14, true)))
    }

    fun testTheDebuggerActions() {
        assertEquals(PhaenonStepping.Step.OVER, PhaenonStepping.ACTIONS["StepOver"])
        assertEquals(PhaenonStepping.Step.INTO, PhaenonStepping.ACTIONS["StepInto"])
        assertTrue("Resume" in PhaenonStepping.ACTIONS && PhaenonStepping.ACTIONS["Resume"] == null)
        assertTrue("StepOut" in PhaenonStepping.ACTIONS && PhaenonStepping.ACTIONS["StepOut"] == null)
        assertFalse("EditorCopy" in PhaenonStepping.ACTIONS)
    }
}
