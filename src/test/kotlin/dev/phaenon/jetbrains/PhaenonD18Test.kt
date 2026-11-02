package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import com.jetbrains.php.lang.inspections.PhpInconsistentReturnPointsInspection
import com.jetbrains.php.lang.inspections.PhpUnusedAliasInspection
import com.jetbrains.php.lang.inspections.controlFlow.PhpNeverTypedFunctionReturnViolationInspection
import com.jetbrains.php.lang.inspections.phpdoc.PhpDocMissingThrowsInspection
import com.jetbrains.php.lang.inspections.probablyBug.PhpUnhandledExceptionInspection
import com.jetbrains.php.lang.inspections.type.PhpIncompatibleReturnTypeInspection
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.GroupStatement
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReturn

/**
 * D18 in `.phn` files (design/functions.md §3–§5): expression bodies `=> expr;`, `throws A|B`, and
 * the canonical layout, on `testData/d18/Bodies.phn` (every kind of declaration, attributes and
 * docblocks around them). PhpStorm parses neither form: an arrow after a signature and a name after
 * a return type are syntax errors to it in any PHP file.
 */
class PhaenonD18Test : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        // PhpStorm's own stub of `\Exception` and `\Throwable`: the test project has none.
        PhpLanguage::class.java.classLoader.getResource("stubs/Core/Core_c.php")?.readText()?.let { myFixture.addFileToProject("stubs/Core_c.php", it) }
        myFixture.copyFileToProject("d18/Errors/Errors.phn")
    }

    private fun bodies() = myFixture.configureByFiles("d18/Bodies.phn").single()

    private fun functions(): Map<String, Function> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Function::class.java).filter { !it.isClosure }.associateBy { (PsiTreeUtil.getParentOfType(it, PhpClass::class.java)?.name ?: "") + "::" + it.name }

    private fun findings(): List<String> = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} @'${it.text}'" }

    fun testBothFormsParseInEveryKindOfDeclaration() {
        bodies()

        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
        val functions = functions()
        // An expression body is the block the compiler writes: one `return`, or one statement under void and never.
        for (name in listOf("Greets::greet", "Level::label", "Directory::find", "Directory::all", "Directory::count", "Directory::first", "Directory::of", "::twice")) {
            val body = PsiTreeUtil.getChildOfType(functions.getValue(name), GroupStatement::class.java)!!
            assertTrue(name, body.statements.single() is PhpReturn)
        }
        for (name in listOf("Directory::log", "Directory::fail")) {
            val body = PsiTreeUtil.getChildOfType(functions.getValue(name), GroupStatement::class.java)!!
            assertFalse(name, body.statements.single() is PhpReturn)
        }
        assertEquals(listOf("NotFound"), PhaenonErasedTypes.throwsNames(functions.getValue("Names::find")))
        assertEquals(listOf("NotFound", "Unavailable"), PhaenonErasedTypes.throwsNames(functions.getValue("Directory::first")))
        assertEquals(listOf("\\LogicException"), PhaenonErasedTypes.throwsNames(functions.getValue("Directory::fail")))
        assertEquals("?string", PhaenonErasedTypes.writtenReturnType(functions.getValue("Directory::find")))
    }

    fun testAnExpressionBodyIsCodeAndTheReturnTypeStaysDeclared() {
        bodies()
        val functions = functions()

        assertEquals(listOf("\\int"), functions.getValue("Directory::count").type.global(project).typesWithParametrisedParts.toList())
        assertTrue("an erased return type", PhaenonTypeText.LIST_METHODS + "<string>" in functions.getValue("Directory::all").type.global(project).typesWithParametrisedParts)
        val names = PsiTreeUtil.findChildrenOfType(functions.getValue("Directory::find"), FieldReference::class.java).single { it.name == "names" }
        assertEquals("names", (names.resolve() as? com.jetbrains.php.lang.psi.elements.Field)?.name)
    }

    fun testTheValueIsCheckedAsAReturnIs() {
        myFixture.enableInspections(
            PhpIncompatibleReturnTypeInspection::class.java,
            PhpInconsistentReturnPointsInspection::class.java,
            PhpNeverTypedFunctionReturnViolationInspection::class.java,
        )
        bodies()
        // No missing `return`, no value returned under void or never.
        assertEmpty(findings())

        myFixture.configureByText("Bad.phn", "namespace App;\n\nfun bad(): array => new \\Exception();\n\nfun fine(): void => print('x');\n")
        // PhpStorm marks the statement the arrow stands for, from the arrow's `>` on.
        assertEquals(listOf("Return value is expected to be 'array', '\\Exception' returned @'> new \\Exception();'"), findings())
    }

    fun testCompletionInsideAnExpressionBody() {
        myFixture.configureByText("C.phn", "namespace App;\n\nfinal class C {\n    private int \$total = 0;\n\n    fun total(): int => \$this->to<caret>;\n}\n")

        val items = myFixture.completeBasic()?.map { it.lookupString }
        assertTrue(items.toString(), items == null || "total" in items)
        if (items == null) assertTrue(myFixture.editor.document.text.contains("\$this->total;"))
    }

    fun testThrowsNamesAreReferencesThatRenameAndKeepTheirImports() {
        myFixture.enableInspections(PhpUnusedAliasInspection::class.java)
        bodies()

        val target = PhpIndex.getInstance(project).getClassesByFQN("\\App\\Errors\\NotFound").single()
        val usages = myFixture.findUsages(target).map { it.element?.text }
        assertTrue(usages.toString(), usages.count { it != null && it.startsWith("throws") } >= 3)
        assertEmpty(findings().filter { it.startsWith("Import") })

        WriteCommandAction.runWriteCommandAction(project) { OptimizeImportsProcessor(project, myFixture.file).runWithoutProgress() }
        assertTrue(myFixture.file.text.contains("use App\\Errors\\NotFound;\nuse App\\Errors\\Unavailable;\n"))

        myFixture.renameElement(target, "Missing")
        assertTrue(myFixture.file.text, myFixture.file.text.contains("fun find(int \$id): ?string throws Missing;"))
        assertTrue(myFixture.file.text, myFixture.file.text.contains("fun first(): string throws Missing|Unavailable =>"))
    }

    fun testCompletionAfterThrowsOffersThrowables() {
        myFixture.configureByText("T.phn", "namespace App\\Errors;\n\nfinal class Plain {\n}\n\nfun f(): void throws <caret> {\n}\n")

        val items = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue(items.toString(), "NotFound" in items && "Unavailable" in items)
        assertFalse(items.toString(), "Plain" in items)
        assertFalse(items.toString(), "int" in items)
    }

    fun testUnhandledExceptionsTheClauseNamesAreHandled() {
        myFixture.enableInspections(PhpUnhandledExceptionInspection::class.java)
        myFixture.addFileToProject(
            "risky.php",
            "<?php\nnamespace App;\n\n/**\n * @throws Errors\\NotFound\n * @throws Errors\\Unavailable\n */\nfunction risky(): void {\n}\n",
        )
        myFixture.configureByText(
            "U.phn",
            """
            namespace App;

            use App\Errors\NotFound;
            use App\Errors\Unavailable;

            interface Finder {
                fun find(int ${'$'}id): string throws NotFound;
            }

            final class U implements Finder {
                fun declared(): void throws NotFound {
                    throw new NotFound(1);
                }

                fun parent(): void throws \Exception {
                    throw new Unavailable();
                }

                fun other(): void throws NotFound {
                    throw new Unavailable();
                }

                fun both(): void throws NotFound|Unavailable {
                    risky();
                }

                fun half(): void throws NotFound {
                    risky();
                }

                fun closure(): \Closure throws NotFound {
                    return function (): void {
                        throw new NotFound(2);
                    };
                }

                fun find(int ${'$'}id): string => throw new NotFound(${'$'}id);
            }

            final class Documented implements Finder {
                /** Finds nothing. */
                fun find(int ${'$'}id): string => throw new NotFound(${'$'}id);
            }
            """.trimIndent() + "\n",
        )

        // PhpStorm reads `@throws` tags only; the clause the compiler writes as one is read by the
        // filter: a closure's exceptions against the enclosing function's clause, a method without a
        // docblock against the clause of the method it implements, as PhpStorm reads tags. A call
        // reported for two exceptions stays unless the clause names both; a method with a docblock
        // of its own does not inherit.
        assertEquals(
            listOf("Unhandled Errors\\Unavailable @'throw'", "Unhandled exceptions @'risky'", "Unhandled Errors\\NotFound @'throw'"),
            findings(),
        )
    }

    fun testAMissingThrowsTagTheClauseNamesIsNotReported() {
        myFixture.enableInspections(PhpDocMissingThrowsInspection().apply { SKIP_ON_EMPTY_PHPDOC = false })
        myFixture.configureByText(
            "M.phn",
            """
            namespace App;

            use App\Errors\NotFound;
            use App\Errors\Unavailable;

            final class M {
                /** Declared. */
                fun declared(): void throws NotFound {
                    throw new NotFound(1);
                }

                /** Not declared. */
                fun other(): void throws NotFound {
                    throw new Unavailable();
                }
            }
            """.trimIndent() + "\n",
        )

        val missing = myFixture.doHighlighting().filter { it.description == PhpBundle.message("inspection.doc.throws.problem") }
        val document = myFixture.editor.document
        assertEquals(listOf("    /** Not declared. */"), missing.map { document.getText(TextRange(document.getLineStartOffset(document.getLineNumber(it.startOffset)), document.getLineEndOffset(document.getLineNumber(it.startOffset)))) }.distinct())
    }

    fun testQuickDocumentationShowsTheClause() {
        bodies()

        assertEquals("fun first(): string throws NotFound|Unavailable", myFixture.hint("fun first", 5))
    }

    fun testAPhpFileKeepsItsSyntaxErrors() {
        myFixture.configureByText("P.php", "<?php\nfunction f(): int => 1;\n")
        assertNotEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        assertNull(PsiTreeUtil.findChildOfType(myFixture.file, PhpClass::class.java))
    }
}
