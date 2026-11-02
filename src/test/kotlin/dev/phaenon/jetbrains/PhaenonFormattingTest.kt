package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.formatter.PhpFormattingModelBuilder
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import java.io.File

/**
 * PhpStorm's PHP formatter works on `.phn` files ([PhaenonFormattingModelBuilder]): Reformat Code,
 * the indent of a new line, and the reformatting that follows code an action inserts. PHP is
 * formatted exactly as in a `.php` file; around an erased construct the formatter keeps what belongs
 * together together and never changes the construct itself. The tests use PhpStorm's PSR-12 style.
 */
class PhaenonFormattingTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
    }

    fun testTheFormatterOfAPhnFileIsThePluginsAndOfAPhpFilePhpStorms() {
        val phn = myFixture.configureByText("A.phn", "namespace App;\n")
        val php = myFixture.configureByText("A.php", "<?php\nnamespace App;\n")

        assertInstanceOf(LanguageFormatting.INSTANCE.forContext(phn), PhaenonFormattingModelBuilder::class.java)
        assertInstanceOf(LanguageFormatting.INSTANCE.forContext(php), PhpFormattingModelBuilder::class.java)
        val root = PhaenonFormattingModelBuilder().createModel(FormattingContext.create(phn, CodeStyle.getSettings(phn))).rootBlock
        assertFalse("the whole file was one read-only leaf", root.isLeaf)
    }

    fun testPhpInAPhnFileIsFormattedAsInAPhpFile() {
        // Plain.phn is PHP only (the compiled AbstractEventSourcedRepository.php of tequila/core),
        // with its indentation and some spacing lost.
        val source = File(testDataPath, "formatting/Plain.phn").readText()

        val phn = reformatted("Plain.phn", source)
        // PSR-12 puts a blank line after the open tag, which a .phn file does not have.
        val php = reformatted("Plain.php", "<?php\n" + source).removePrefix("<?php\n").trimStart('\n')

        assertEquals(php, phn)
        assertTrue(phn.contains("\n    public function get(AggregateId \$id): ?EventSourcedAggregate\n    {\n        \$events = "))
        assertTrue(phn.contains("\n        if (0 === count(\$events)) {\n"))
    }

    fun testErasedConstructsAreFormattedAroundAndNeverInside() {
        // `Box <T>` → `Box<T>`, `) : T` → `): T`, `list<T>  $items` → `list<T> $items`, `$x   as   T` →
        // `$x as T`, `new Box<U> (` → `new Box<U>(`; the text of each construct stays as written.
        val file = myFixture.configureByFile("formatting/Erased.phn")
        val erased = erasedTexts(file)

        reformat(file)

        myFixture.checkResultByFile("formatting/Erased.after.phn")
        assertEquals(erased, erasedTexts(myFixture.file))
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java))
    }

    fun testARealSourceComesBackFromLostIndentation() {
        // tequila/core's AbstractEventSourcedRepository.phn with every line indented by one space
        // (column 0 would keep its line comments there, as PhpStorm keeps them in PHP). The result
        // is the source as php-cs-fixer left it, except that PhpStorm's PSR-12 style wraps the two
        // lines longer than 120 columns; the erased type parameters stay on their declarations.
        val source = File(testDataPath, "formatting/AbstractEventSourcedRepository.phn").readText()
        val flat = source.lines().joinToString("\n") { if (it.isBlank()) it else " " + it.trimStart() }

        val restored = reformatted("AbstractEventSourcedRepository.phn", flat)

        assertEquals(File(testDataPath, "formatting/AbstractEventSourcedRepository.after.phn").readText(), restored)
        assertEquals(restored, reformatted("Source.phn", source))
    }

    fun testANewLineIsIndented() {
        myFixture.configureByText("E.phn", "namespace App;\n\nfinal class E\n{\n    fun f(): void\n    {\n        if (true) {<caret>\n    }\n}\n")

        myFixture.type("\n")

        myFixture.checkResult("namespace App;\n\nfinal class E\n{\n    fun f(): void\n    {\n        if (true) {\n            <caret>\n        }\n    }\n}\n")
    }

    fun testAnImportAddedByCompletionGetsALineOfItsOwn() {
        myFixture.addFileToProject("Model.phn", "namespace App\\Model;\n\nfinal class EventStoreFactory\n{\n}\n")
        val text = "namespace App;\n\nfinal class A\n{\n    fun f(): void\n    {\n        \$x = new EventStoreFac<caret>\n    }\n}\n"
        myFixture.configureByText("A.phn", text)

        myFixture.completeBasic()?.let { myFixture.lookup.currentItem = it.first(); myFixture.finishLookup('\n') }

        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("namespace App;\n\nuse App\\Model\\EventStoreFactory;\n\nfinal class A\n"))
    }

    private fun reformatted(name: String, text: String): String {
        val file = myFixture.configureByText(name, text)
        reformat(file)
        return myFixture.editor.document.text
    }

    private fun reformat(file: PsiFile) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
    }

    private fun erasedTexts(file: PsiFile): List<String> =
        PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }
}
