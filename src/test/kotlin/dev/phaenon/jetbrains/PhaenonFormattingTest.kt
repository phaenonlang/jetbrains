package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.PhpCodeStyleSettings
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

    fun testPhpInAPhnFileIsFormattedAsInAPhpFileBracesAside() {
        // Plain.phn is PHP only (AbstractLedgerBackedRepository.phn as `phn build` compiles it, its open
        // tag, banner, strict_types and line map left out), with its indentation and some spacing lost.
        val source = File(testDataPath, "formatting/Plain.phn").readText()

        val phn = reformatted("Plain.phn", source)
        // The .php file formatted with the brace placement and empty bodies of Phaenon's canonical
        // layout — the settings a .phn changes — gives the same text. PSR-12 puts a blank line after the open tag,
        // which a .phn file does not have.
        val php = CommonCodeStyleSettings.END_OF_LINE.let { endOfLine ->
            val settings = CodeStyle.getSettings(project).getCommonSettings(PhpLanguage.INSTANCE)
            settings.CLASS_BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE
            settings.METHOD_BRACE_STYLE = endOfLine
            settings.LAMBDA_BRACE_STYLE = endOfLine
            // Plain.phn has no closure, so PhpStorm's option writes the empty constructor body as a .phn does.
            CodeStyle.getSettings(project).getCustomSettings(PhpCodeStyleSettings::class.java).FORCE_EMPTY_METHODS_IN_ONE_LINE = true
            reformatted("Plain.php", "<?php\n" + source).removePrefix("<?php\n").trimStart('\n')
        }

        assertEquals(php, phn)
        assertTrue(phn.contains("\nabstract readonly class AbstractLedgerBackedRepository implements ItemRepository\n{\n"))
        assertTrue(phn.contains("\n    public function find(ItemCode \$code): ?StockedItem {\n        \$entries = "))
        assertTrue(phn.contains("\n        if (0 === count(\$entries)) {\n"))
    }

    fun testOpeningBracesOfDeclarationsStandOnTheirLine() {
        // PhpStorm's PSR-12 style puts a function's and a method's on a line of their own; a .phn
        // file keeps the canonical layout whatever the PHP code style says (design/functions.md
        // §5): a function's, a method's and a closure's brace on the declaration's line, a
        // class-like's on a line of its own — moved there when it was written on the line.
        val phn = reformatted(
            "Braces.phn",
            "namespace App;\n\nfinal class A\n{\n    f(): void\n    {\n        \$g = function ()\n        {\n            return 1;\n        };\n    }\n}\n\n" +
                "interface I {\n    i(): void;\n}\n\ntrait T {\n    t(): void {}\n}\n\nenum E: int {\n    case One = 1;\n}\n\nfunction g(): object\n{\n    return new class {\n    };\n}\n",
        )

        assertEquals(
            "namespace App;\n\nfinal class A\n{\n    f(): void {\n        \$g = function () {\n            return 1;\n        };\n    }\n}\n\n" +
                "interface I\n{\n    i(): void;\n}\n\ntrait T\n{\n    t(): void {}\n}\n\nenum E: int\n{\n    case One = 1;\n}\n\nfunction g(): object {\n    return new class {\n    };\n}\n",
            phn,
        )
        val php = reformatted("Braces.php", "<?php\nnamespace App;\n\nfinal class A {\n    public function f(): void {\n    }\n}\n")
        assertTrue("a .php file keeps PSR-12's\n$php", php.contains("final class A\n{\n    public function f(): void\n    {\n"))
    }

    /**
     * An empty function or method body — nothing but whitespace between its braces — is `{}` on the
     * declaration's line (design/functions.md §5.1), from either brace placement and after a
     * multi-line parameter list; a body with a comment or a statement keeps its lines, a closure is
     * PhpStorm's, a class-like keeps its brace on a line of its own.
     */
    fun testAnEmptyBodyIsTwoBracesOnTheDeclarationsLine() {
        val phn = reformatted(
            "Empty.phn",
            """
            namespace App;

            final class Repository
            {
                __construct(private EventStoreFactory ${'$'}eventStoreFactory) {
                }

                psr12(): void
                {
                }

                __clone(): void { }

                written(): void {}

                wide(
                    private A ${'$'}a,
                    private B ${'$'}b,
                ) {
                }

                reset(): void throws Failure {
                }

                commented(): void {
                    // nothing yet
                }

                busy(): void {
                    ${'$'}this->reset();
                }

                annotated(): void // the reason
                {
                }

                abstract none(): void;
            }

            function noBody(): void {
            }

            ${'$'}closure = function () {
            };

            interface Blank {}
            """.trimIndent() + "\n",
        )

        assertEquals(
            """
            namespace App;

            final class Repository
            {
                __construct(private EventStoreFactory ${'$'}eventStoreFactory) {}

                psr12(): void {}

                __clone(): void {}

                written(): void {}

                wide(
                    private A ${'$'}a,
                    private B ${'$'}b,
                ) {}

                reset(): void throws Failure {}

                commented(): void {
                    // nothing yet
                }

                busy(): void {
                    ${'$'}this->reset();
                }

                annotated(): void // the reason
                {
                }

                abstract none(): void;
            }

            function noBody(): void {}

            ${'$'}closure = function () {
            };

            interface Blank {}
            """.trimIndent() + "\n",
            phn,
        )
        assertEquals(phn, reformatted("Again.phn", phn))
        val php = reformatted("Empty.php", "<?php\nnamespace App;\n\nfinal class A\n{\n    public function f(): void {}\n}\n")
        assertTrue("a .php file keeps PSR-12's\n$php", php.contains("    public function f(): void\n    {\n    }\n"))
    }

    /**
     * An empty class-like — no member, no comment between its braces — is `{}` on its header's line
     * (design/functions.md §5.1), whichever line its brace was on: a class with type parameters and
     * a generic parent, an enum with a backing type, an interface, a trait, an attributed class. One
     * with a member or a comment keeps its brace on a line of its own, as does one whose brace
     * follows a line comment; an anonymous class is PhpStorm's.
     */
    fun testAnEmptyClassLikeIsTwoBracesOnItsHeadersLine() {
        val phn = reformatted(
            "Classes.phn",
            """
            namespace App;

            final class AggregateNotFound extends \DomainException
            {
            }

            final class Box<T of object = object> extends Base<T> implements Holder<T> {
            }

            enum Level: int
            {
            }

            interface Marker {   }

            trait Nothing
            {

            }

            #[\Attribute]
            final class Tag {}

            final class Constants {
                const ONE = 1;
            }

            final class Later
            {
                // members to come
            }

            final class Explained // why it is empty
            {
            }

            ${'$'}anonymous = new class {
            };
            """.trimIndent() + "\n",
        )

        assertEquals(
            """
            namespace App;

            final class AggregateNotFound extends \DomainException {}

            final class Box<T of object = object> extends Base<T> implements Holder<T> {}

            enum Level: int {}

            interface Marker {}

            trait Nothing {}

            #[\Attribute]
            final class Tag {}

            final class Constants
            {
                const ONE = 1;
            }

            final class Later
            {
                // members to come
            }

            final class Explained // why it is empty
            {
            }

            ${'$'}anonymous = new class {
            };
            """.trimIndent() + "\n",
            phn,
        )
        assertEquals(phn, reformatted("Again.phn", phn))
        val php = reformatted("Classes.php", "<?php\nnamespace App;\n\nfinal class A {}\n")
        assertTrue("a .php file keeps PSR-12's\n$php", php.contains("final class A\n{\n}\n"))
    }

    /** Under PhpStorm's PER style the empty bodies are the same; a closure and an anonymous class are PER's (`{}` too). */
    fun testUnderPerStyle() {
        com.jetbrains.php.lang.formatter.ui.predefinedStyle.PERCodeStyle().apply(CodeStyle.getSettings(project))
        val phn = reformatted(
            "Per.phn",
            "namespace App;\n\nfinal class A {}\n\nfinal class B\n{\n}\n\nfinal class C\n{\n    const X = 1;\n}\n\nfunction f(): void {\n}\n\n\$g = function () {\n};\n\n\$o = new class {\n};\n",
        )
        assertEquals(
            "namespace App;\n\nfinal class A {}\n\nfinal class B {}\n\nfinal class C\n{\n    const X = 1;\n}\n\nfunction f(): void {}\n\n\$g = function () {};\n\n\$o = new class {};\n",
            phn,
        )
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

    fun testARepositoryComesBackFromLostIndentation() {
        // AbstractLedgerBackedRepository.phn, a generic repository with its braces where PSR-12
        // puts them, with every line indented by one space (column 0 would keep its line comments
        // there, as PhpStorm keeps them in PHP). The result is the source in Phaenon's layout (the
        // methods' braces on their line, the empty constructor `{}`), except that PhpStorm's PSR-12
        // style wraps the two lines longer than 120 columns; the erased type parameters stay on their
        // declarations.
        val source = File(testDataPath, "formatting/AbstractLedgerBackedRepository.phn").readText()
        val flat = source.lines().joinToString("\n") { if (it.isBlank()) it else " " + it.trimStart() }

        val restored = reformatted("AbstractLedgerBackedRepository.phn", flat)

        assertEquals(File(testDataPath, "formatting/AbstractLedgerBackedRepository.after.phn").readText(), restored)
        assertEquals(restored, reformatted("Source.phn", source))
    }

    fun testANewLineIsIndented() {
        myFixture.configureByText("E.phn", "namespace App;\n\nfinal class E\n{\n    f(): void\n    {\n        if (true) {<caret>\n    }\n}\n")

        myFixture.type("\n")

        myFixture.checkResult("namespace App;\n\nfinal class E\n{\n    f(): void\n    {\n        if (true) {\n            <caret>\n        }\n    }\n}\n")
    }

    fun testAnImportAddedByCompletionGetsALineOfItsOwn() {
        myFixture.addFileToProject("Model.phn", "namespace App\\Model;\n\nfinal class EventStoreFactory\n{\n}\n")
        val text = "namespace App;\n\nfinal class A\n{\n    f(): void\n    {\n        \$x = new EventStoreFac<caret>\n    }\n}\n"
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
