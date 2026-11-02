package dev.phaenon.jetbrains

import com.intellij.execution.filters.CompositeFilter
import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.phpunit.PhpUnitQualifiedNameLocationProvider
import com.jetbrains.php.run.filters.PhpErrorMessageFilter
import com.jetbrains.php.run.filters.PhpStackTraceFilterProvider
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import java.io.File

/**
 * A position in a compiled file opens the `.phn` at the forward-mapped line: console links in
 * the three spellings, ahead of PhpStorm's own link on the same text, and every navigation that
 * opens a compiled file at a position — PHPUnit's test locations among them. The fixture is
 * `testData/linemap/project`, copied to disk: console paths are file system paths.
 */
class PhaenonNavigationTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private lateinit var root: File
    private lateinit var source: VirtualFile
    private lateinit var compiled: VirtualFile
    private lateinit var plain: VirtualFile

    override fun setUp() {
        super.setUp()
        root = FileUtil.createTempDirectory("phaenon", "navigation")
        FileUtil.copyDir(File(testDataPath, "linemap/project"), root)
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!
        dir.refresh(false, true)
        source = dir.findFileByRelativePath("src/Domain/Greeter.phn")!!
        compiled = dir.findFileByRelativePath(".src/Domain/Greeter.php")!!
        plain = dir.findFileByRelativePath("bin/run.php")!!
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) }
        } finally {
            super.tearDown()
        }
    }

    private val path: String get() = compiled.path

    private fun links(line: String, before: Int = 100): List<Pair<String, Int>> {
        val result = PhaenonCompiledLinkFilter(project).applyFilter(line, before + line.length) ?: return emptyList()
        return result.resultItems.map { item ->
            val descriptor = (item.hyperlinkInfo as OpenFileHyperlinkInfo).descriptor!!
            val text = line.substring(item.highlightStartOffset - before, item.highlightEndOffset - before)
            "${descriptor.file.name}:${lineOf(descriptor)} ($text)" to item.highlightStartOffset
        }
    }

    /** The 0-based line a descriptor opens: `OpenFileHyperlinkInfo` turns its line into an offset. */
    private fun lineOf(descriptor: OpenFileDescriptor): Int =
        if (descriptor.line >= 0) descriptor.line
        else com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(descriptor.file)!!.getLineNumber(descriptor.offset)

    fun testTheThreeSpellingsLinkToTheSource() {
        // PHPUnit and PHPStan's raw format: `path:line`, `path:line:message`. Compiled line 14 is source line 6 (0-based 5).
        assertEquals(listOf("Greeter.phn:5 ($path:14)"), links("  $path:14").map { it.first })
        assertEquals(listOf("Greeter.phn:11 ($path:25)"), links("$path:25:Method App\\Domain\\Greeter::twice() …").map { it.first })
        // getTraceAsString(): `path(line)`.
        assertEquals(listOf("Greeter.phn:10 ($path(24))"), links("#0 $path(24): App\\Domain\\Greeter->greet('ada')").map { it.first })
        // PHP's own messages: `in path on line N`.
        assertEquals(listOf("Greeter.phn:14 ($path on line 30)"), links("PHP Warning:  Undefined variable \$x in $path on line 30").map { it.first })
        // A file:// URL, and two links on one line.
        assertEquals(listOf("Greeter.phn:10 (file://$path:24)"), links("at file://$path:24").map { it.first })
        assertEquals(2, links("$path:14 and $path(24)").size)
    }

    fun testTheRangeIsWhereTheTextIs() {
        val line = "#0 $path(24): x"
        val (_, start) = links(line, before = 37).single()
        assertEquals(37 + 3, start)
    }

    fun testOtherFilesAreLeftToPhpStorm() {
        assertEmpty(links("${plain.path}:3"))
        assertEmpty(links("/nowhere/at/all.php:3"))
        assertEmpty(links("no path here: 42"))
    }

    /** First in order, the link to the source replaces PhpStorm's link to the compiled line on the same text. */
    fun testTheSourceLinkWinsOverPhpStormsOwn() {
        val providers: List<Class<*>> = ConsoleFilterProvider.FILTER_PROVIDERS.extensionList.map { it.javaClass }
        val ours = providers.indexOf(PhaenonCompiledLinkFilterProvider::class.java)
        assertTrue(ours >= 0 && ours < providers.indexOf(PhpStackTraceFilterProvider::class.java))
        // PhpStorm's filter for PHP's own messages (its `path:line` filter links paths under the project's base only).
        val php = PhpErrorMessageFilter(project, PhpPathMapper.createDefaultMapper())
        val line = "PHP Warning:  Undefined variable \$x in $path on line 14\n"
        assertEquals(compiled, (php.applyFilter(line, line.length)!!.firstHyperlinkInfo as OpenFileHyperlinkInfo).descriptor!!.file)
        val composite = CompositeFilter(project, listOf<Filter>(PhaenonCompiledLinkFilter(project), php))
        val descriptor = (composite.applyFilter(line, line.length)!!.firstHyperlinkInfo as OpenFileHyperlinkInfo).descriptor!!
        assertEquals(source, descriptor.file)
        assertEquals(5, lineOf(descriptor))
    }

    fun testTheCompiledFileGetsTheRedirectingEditor() {
        val provider = PhaenonCompiledEditorProvider()
        assertTrue(provider.accept(project, compiled))
        assertFalse(provider.accept(project, plain))
        assertFalse(provider.accept(project, source))
    }

    private fun navigateInCompiledEditor(descriptor: OpenFileDescriptor) {
        val editor = PhaenonCompiledEditorProvider().createEditor(project, compiled) as PhaenonCompiledTextEditor
        try {
            editor.navigateTo(descriptor)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            Disposer.dispose(editor)
        }
    }

    /** Opening the compiled file at a line — a console link of PhpStorm's, a quality tool's result — lands in the source. */
    fun testOpeningTheCompiledFileAtALineOpensTheSource() {
        navigateInCompiledEditor(OpenFileDescriptor(project, compiled, 29, 0))
        val selected = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(source, selected.virtualFile)
        assertEquals(14, selected.caretModel.logicalPosition.line)
    }

    /** PHPUnit's test tree locates a test in the compiled file it ran (`php_qn://…`); that position lands in the source too. */
    fun testATestLocationLandsInTheSource() {
        val locator = PhpUnitQualifiedNameLocationProvider.create(PhpPathMapper.createDefaultMapper())
        val location = locator.getLocation("php_qn", "$path::\\App\\Domain\\Greeter::twice", project, GlobalSearchScope.allScope(project)).single()
        val descriptor = location.openFileDescriptor!!
        assertEquals(compiled, descriptor.file)
        navigateInCompiledEditor(descriptor)
        val selected = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(source, selected.virtualFile)
        assertEquals(9, selected.caretModel.logicalPosition.line)
    }

    fun testTheBannerNamesTheSource() {
        assertNotNull(PhaenonCompiledNotificationProvider().collectNotificationData(project, compiled))
        assertNull(PhaenonCompiledNotificationProvider().collectNotificationData(project, plain))
    }
}
