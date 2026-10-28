package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.stubs.PhpFileElementType
import java.io.File

/**
 * Runs against a headless PhpStorm (the IntelliJ test framework), so what is asserted here is the
 * real PHP plugin behaviour, not a mock of it.
 */
class PhaenonFileTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testPhnFilesArePhpFilesWithAPhaenonRoot() {
        val file = myFixture.configureByFile("reflector/AttributeReader.phn")

        assertEquals(PhpFileType.INSTANCE, file.virtualFile.fileType)
        assertInstanceOf(file, PhpFile::class.java)
        assertSame(PhpLanguage.INSTANCE, file.language)
        assertInstanceOf(file.viewProvider, PhaenonFileViewProvider::class.java)
        assertSame(PhaenonFileElementType, file.node.elementType)
    }

    fun testEveryFixtureParsesAsPhpCodeRatherThanInlineHtml() {
        val fixtures = File(testDataPath, "reflector").list { _, name -> name.endsWith(".phn") }!!.sorted()
        assertTrue("no .phn fixtures found", fixtures.isNotEmpty())

        for (name in fixtures) {
            val file = myFixture.configureByFile("reflector/$name")
            val tokens = leafTokenTypes(file)

            assertFalse("$name was lexed as inline HTML", PhpTokenTypes.HTML in tokens)
            assertFalse("$name has syntax errors", PsiTreeUtil.hasErrorElements(file))
            assertNotNull("$name declares no class, interface or enum", PsiTreeUtil.findChildOfType(file, PhpClass::class.java))
            PsiTestUtil.checkStubsMatchText(file)
        }
    }

    fun testEditorHighlighterStartsInPhpCodeMode() {
        val file = myFixture.configureByFile("reflector/FakeLabel.phn")
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file.virtualFile)
        highlighter.setText(file.text)

        val tokens = buildList {
            val iterator = highlighter.createIterator(0)
            while (!iterator.atEnd()) {
                add(iterator.tokenType)
                iterator.advance()
            }
        }

        assertEquals(PhpTokenTypes.kwNAMESPACE, tokens.first())
        assertTrue(PhpTokenTypes.kwCLASS in tokens)
        assertFalse(PhpTokenTypes.HTML in tokens)
    }

    fun testPlainPhpFilesAreLeftAlone() {
        val file = myFixture.configureByText("plain.php", "final class StillHtml {}\n")

        assertSame(PhpLanguage.INSTANCE, file.language)
        assertFalse(file.viewProvider is PhaenonFileViewProvider)
        assertSame(PhpFileElementType.FILE, file.node.elementType)
        assertTrue("a .php file without an open tag must still be inline HTML", PhpTokenTypes.HTML in leafTokenTypes(file))
    }

    fun testCompletionOffersMembersDeclaredInPhaenonCode() {
        myFixture.configureByText(
            "Greeter.phn",
            """
            namespace App;

            final class Greeter
            {
                public function greet(string ${'$'}name): string
                {
                    return "Hello ${'$'}name";
                }

                public function greetLoudly(string ${'$'}name): string
                {
                    return strtoupper(${'$'}this->gr<caret>);
                }
            }
            """.trimIndent(),
        )

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings!!, "greet", "greetLoudly")
    }

    fun testClassesInPhnFilesAreIndexedAndResolvableFromPhaenonCode() {
        myFixture.configureByFiles("navigation/Consumer.phn", "navigation/Label.phn")

        val target = myFixture.elementAtCaret
        assertInstanceOf(target, PhpClass::class.java)
        assertEquals("\\App\\Label", (target as PhpClass).fqn)
        assertEquals("Label.phn", target.containingFile.name)
        assertSize(1, PhpIndex.getInstance(project).getClassesByFQN("\\App\\Label"))
    }

    fun testClassesInPhnFilesAreResolvableFromPlainPhp() {
        myFixture.configureByFiles("navigation/consumer.php", "navigation/Label.phn")

        // `new Label(...)` targets the constructor, as it does for any PHP class; what matters is
        // that it lands inside the .phn file, which proves the class went through the stub index.
        val target = myFixture.elementAtCaret
        assertEquals("Label.phn", target.containingFile.name)
        val owner = target as? PhpClass ?: PsiTreeUtil.getParentOfType(target, PhpClass::class.java)
        assertEquals("\\App\\Label", owner?.fqn)
    }

    fun testPhpOpenTagIsAnErrorInPhaenonSource() {
        myFixture.configureByText("Legacy.phn", "<?php\n\nfinal class Legacy {}\n")

        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }

        assertEquals("expected only the open tag to be reported, got: $errors", 1, errors.size)
        assertEquals("<?php", errors.single().text)
        assertTrue(errors.single().description.contains("open and close tags"))
    }

    fun testFixturesProduceNoErrorHighlighting() {
        for (name in listOf("AttributeReader.phn", "CachedReflector.phn", "ReflectorInterface.phn", "FakeSuit.phn")) {
            myFixture.configureByFile("reflector/$name")
            val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
            assertEmpty("$name has error-level highlighting: $errors", errors)
        }
    }

    fun testReformatKeepsTopLevelCodeAtColumnZero() {
        val file = myFixture.configureByFile("navigation/Label.phn")
        val before = file.text

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }

        assertEquals(before, file.text)
    }

    private fun leafTokenTypes(file: PsiFile): Set<IElementType> {
        val types = LinkedHashSet<IElementType>()
        PsiTreeUtil.processElements(file) { element ->
            if (element.node is LeafElement) types.add(element.node.elementType)
            true
        }
        return types
    }
}
