package dev.phaenon.jetbrains

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade

/**
 * Rename and a `type` alias. PhpStorm knows one kind of an alias's uses, the plugin's references
 * inside erased constructs: its rename, started on one, renamed `list<Line>` and `Page<Line>` and
 * left `type Line = …;`, the `use` and every signature. Until all of them are renamed, none is:
 * from the declaration and from each kind of use the answer is one message, and no file changes.
 * Over the files of `testData/shapes/shop`.
 */
class PhaenonAliasRenameTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    private val files = listOf("src/Orders/types.phn", "src/Orders/Orders.phn", "src/Http/Uses.phn", "src/Http/OrderController.phn")

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("shapes/shop", "src")
    }

    private fun caretAt(path: String, needle: String, shift: Int): DataContext {
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(needle).also { check(it >= 0) { needle } } + shift)
        return (myFixture.editor as EditorEx).dataContext
    }

    private fun contents(): List<String> = files.map { String(myFixture.findFileInTempDir(it).contentsToByteArray()) }

    fun testRenameOnAnAliasSaysItIsNotSupportedFromEveryPosition() {
        val before = contents()
        val positions = listOf(
            Triple("src/Http/Uses.phn", "lines(): list<Line>", "lines(): list<".length + 1), // an erased construct
            Triple("src/Http/Uses.phn", "private Page<Line> \$page", "private Page<".length + 1), // a generic alias's argument
            Triple("src/Http/Uses.phn", "line(): Line =>", "line(): ".length + 1), // a signature
            Triple("src/Http/Uses.phn", "{Line, Order as Placed}", 2), // a `use`
            Triple("src/Http/Uses.phn", "@var list<Line>", "@var list<".length + 1), // a docblock
            Triple("src/Orders/types.phn", "type Line =", "type ".length + 1), // the declaration
            Triple("src/Orders/types.phn", "type Line =", "type Line".length), // the caret behind the name
            Triple("src/Orders/types.phn", "lines: list<Line>", "lines: list<".length + 1), // another alias's shape
        )
        for ((path, needle, shift) in positions) {
            val context = caretAt(path, needle, shift)
            val handlers = RenameHandlerRegistry.getInstance().getRenameHandlers(context)
            assertEquals("$path `$needle`", listOf("PhaenonAliasRenameHandler"), handlers.map { it.javaClass.simpleName })
            try {
                handlers.single().invoke(project, myFixture.editor, myFixture.file, context)
                fail("$path `$needle`: the handler said nothing")
            } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
                assertEquals("$path `$needle`", "Rename is not supported for the type alias 'Line': change the name in its type declaration and in the files that name it.", e.message)
            }
        }
        assertEquals(before, contents())
    }

    fun testRenameIsPhpStormsWhereNoAliasIsNamed() {
        // A class, a shape's key, a keyword: the plugin's answer is for aliases alone.
        for ((path, needle, shift) in listOf(
            Triple("src/Http/Uses.phn", "place(Customer \$buyer)", "place(".length + 1),
            Triple("src/Orders/types.phn", "lines: list<Line>", 1),
            Triple("src/Orders/types.phn", "type Line =", 1),
        )) {
            val handlers = RenameHandlerRegistry.getInstance().getRenameHandlers(caretAt(path, needle, shift))
            assertFalse("$path `$needle`", handlers.any { it is PhaenonAliasRenameHandler })
        }
    }

    fun testARenameThatReachesTheElementChangesNothing() {
        // The fixture's rename finds its target itself and goes past the handlers: what it reaches is refused whole.
        val before = contents()
        caretAt("src/Http/Uses.phn", "lines(): list<Line>", "lines(): list<".length + 1)

        val refused = try {
            myFixture.renameElementAtCaret("Item")
            null
        } catch (e: Throwable) {
            generateSequence(e) { it.cause }.map { it.message.orEmpty() }.joinToString(" / ")
        }

        assertNotNull("the rename went through", refused)
        assertTrue(refused, refused!!.contains("Rename is not supported for the type alias 'Line'"))
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals(before, contents())
    }
}
