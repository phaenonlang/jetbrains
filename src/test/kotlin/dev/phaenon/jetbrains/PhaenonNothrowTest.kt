package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import com.jetbrains.php.lang.inspections.PhpInconsistentReturnPointsInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedClassConstantInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedFunctionInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.probablyBug.PhpUnhandledExceptionInspection
import com.jetbrains.php.lang.inspections.type.PhpIncompatibleReturnTypeInspection
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.GroupStatement
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReturn
import java.io.File

/**
 * `nothrow` (design/functions.md §4.5), the clause spelling of `@throws void`, on
 * `testData/d18/Nothrow.phn`: it stands where a `throws` clause does, after a native, a generic or
 * no return type and before a block, an expression body's arrow or an abstract method's `;`, in any
 * case, and names nothing. [PhaenonLexer] erases the word there; everywhere else it is a name.
 */
class PhaenonNothrowTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        // PhpStorm's own stub of `\Exception` and `\Throwable`: the test project has none.
        PhpLanguage::class.java.classLoader.getResource("stubs/Core/Core_c.php")?.readText()?.let { myFixture.addFileToProject("stubs/Core_c.php", it) }
        myFixture.copyFileToProject("d18/Errors/Errors.phn")
    }

    private fun functions(): Map<String, Function> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Function::class.java).filter { !it.isClosure }
            .associateBy { (PsiTreeUtil.getParentOfType(it, PhpClass::class.java)?.name ?: "") + "::" + it.name }

    private fun errors(): List<String> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { "${it.errorDescription} at ${it.textOffset}" }

    private fun erased(): List<String> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }

    private fun findings(): List<String> = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} @'${it.text}'" }

    fun testEveryShapeParsesWithTheWordErased() {
        val file = myFixture.configureByFile("d18/Nothrow.phn")

        assertEmpty(errors())
        PsiTestUtil.checkStubsMatchText(file)
        val functions = functions()
        val declaring = listOf(
            "Finder::find", "Finder::all", "Finder::supports", "Base::label",
            "Names::find", "Names::all", "Names::count", "Names::log", "Names::of", "Names::write", "::twice", "::none",
        )
        for (name in declaring) {
            val function = functions.getValue(name)
            assertNotNull(name, PhaenonErasedTypes.nothrowClause(function))
            assertEmpty(name, PhaenonErasedTypes.throwsNames(function))
        }
        for (name in listOf("Names::supports", "Names::first", "Names::label", "Names::__construct")) {
            assertNull(name, PhaenonErasedTypes.nothrowClause(functions.getValue(name)))
        }
        assertEquals(listOf("NotFound"), PhaenonErasedTypes.throwsNames(functions.getValue("Names::first")))
        // The word alone is erased, as written; the generic return type is a leaf of its own.
        assertEquals(10, erased().count { it == "nothrow" })
        assertEquals(listOf("NoThrow", "NOTHROW"), erased().filter { it != "nothrow" && it.equals("nothrow", ignoreCase = true) })
        assertEquals(2, erased().count { it == ": list<string>" })
        // An expression body after the word is the block it compiles to: a `return`, a statement under void.
        for (name in listOf("Names::all", "Names::of", "::twice")) {
            assertTrue(name, PsiTreeUtil.getChildOfType(functions.getValue(name), GroupStatement::class.java)!!.statements.single() is PhpReturn)
        }
        assertFalse(PsiTreeUtil.getChildOfType(functions.getValue("Names::log"), GroupStatement::class.java)!!.statements.single() is PhpReturn)
    }

    fun testTheReturnTypeEndsBeforeTheWord() {
        myFixture.configureByFile("d18/Nothrow.phn")
        val functions = functions()

        assertEquals("?string", PhaenonErasedTypes.writtenReturnType(functions.getValue("Names::find")))
        assertEquals("list<string>", PhaenonErasedTypes.writtenReturnType(functions.getValue("Names::all")))
        assertEquals("static", PhaenonErasedTypes.writtenReturnType(functions.getValue("Names::of")))
        assertNull(PhaenonErasedTypes.writtenReturnType(functions.getValue("Names::count")))
        assertEquals("", PhaenonErasedTypes.returnType(functions.getValue("Names::find")))
        assertEquals("list<string>", PhaenonErasedTypes.returnType(functions.getValue("Names::all")))
        assertNull(PhaenonErasedTypes.returnTypeLeaf(functions.getValue("::twice")))
        assertEquals(listOf("\\int"), functions.getValue("::twice").type.global(project).typesWithParametrisedParts.toList())
        assertTrue("\\string[]" in functions.getValue("Names::all").type.global(project).typesWithParametrisedParts)
        assertEquals(setOf("\\string", "\\null"), functions.getValue("Names::find").type.global(project).typesWithParametrisedParts.toSet())
    }

    fun testNothingIsReported() {
        // The test project has no PHP stubs, so no undefined-function inspection: `\count` is one.
        myFixture.enableInspections(
            PhpIncompatibleReturnTypeInspection::class.java,
            PhpInconsistentReturnPointsInspection::class.java,
            PhpUndefinedMethodInspection::class.java,
        )
        myFixture.configureByFile("d18/Nothrow.phn")

        assertEmpty(findings())
        // The word names no class.
        for (leaf in PsiTreeUtil.findChildrenOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) && it.text.equals("nothrow", ignoreCase = true) }) {
            assertEmpty(leaf.text, leaf.references.toList())
        }
    }

    /** On the signature's line or a line of its own, and nowhere a clause cannot stand. */
    fun testTheWordIsErasedOnlyWhereAClauseGoes() {
        myFixture.configureByText(
            "Lines.phn",
            "namespace App;\n\nfinal class Lines\n{\n    static of(): static\n        nothrow => new static();\n\n    reset(): void\n        nothrow {}\n}\n",
        )
        assertEmpty(errors())
        assertEquals(listOf("nothrow", "nothrow"), erased())

        // Beside `throws`, twice, before a name, on a closure or an arrow function: a syntax error, as to the compiler.
        for (text in listOf(
            "function f() nothrow throws \\Exception {}",
            "function f(): int throws \\Exception nothrow {}",
            "function f() nothrow nothrow {}",
            "function f() nothrow X {}",
            "\$f = function () nothrow {};",
            "\$f = fn () nothrow => 1;",
        )) {
            myFixture.configureByText("Wrong.phn", "namespace App;\n\n$text\n")
            assertTrue(text, errors().isNotEmpty())
        }
    }

    /**
     * A function, a parameter, a return type, a class, its constant and method, their uses and
     * calls named `nothrow` are PHP's: the lexer hands every one over as an identifier, and only
     * the clause after the method `nothrow()`'s return type `nothrow` is erased.
     */
    fun testTheWordIsANameEverywhereElse() {
        val file = myFixture.configureByText(
            "Names.phn",
            """
            namespace App;

            final class nothrow
            {
                public const nothrow = 1;

                nothrow(): nothrow nothrow => new nothrow();

                static make(nothrow ${'$'}nothrow): int {
                    return ${'$'}nothrow::nothrow;
                }
            }

            const NOTHROW = 2;

            function nothrow(nothrow ${'$'}nothrow): nothrow {
                return ${'$'}nothrow->nothrow();
            }

            function run(): int {
                ${'$'}n = nothrow(new nothrow());

                return nothrow::nothrow + NOTHROW + nothrow::make(${'$'}n);
            }
            """.trimIndent() + "\n",
        )

        assertEmpty(errors())
        assertEquals(listOf("nothrow"), erased())
        val functions = functions()
        assertNotNull(PhaenonErasedTypes.nothrowClause(functions.getValue("nothrow::nothrow")))
        assertNull(PhaenonErasedTypes.nothrowClause(functions.getValue("::nothrow")))
        assertEquals(listOf("\\App\\nothrow"), functions.getValue("nothrow::nothrow").type.global(project).typesWithParametrisedParts.toList())
        assertEquals(listOf("\\App\\nothrow"), functions.getValue("::nothrow").type.global(project).typesWithParametrisedParts.toList())
        val call = PsiTreeUtil.findChildrenOfType(file, FunctionReference::class.java).single { it !is MethodReference && it.name == "nothrow" }
        assertEquals("\\App\\nothrow", (call.resolve() as? Function)?.fqn)
        val method = PsiTreeUtil.findChildrenOfType(file, MethodReference::class.java).single { it.name == "nothrow" }
        assertEquals("nothrow", (method.resolve() as? Method)?.containingClass?.name)
        assertTrue(PsiTreeUtil.findChildrenOfType(file, ClassConstantReference::class.java).filter { it.name == "nothrow" }.all { it.resolve() != null })

        myFixture.enableInspections(
            PhpUndefinedFunctionInspection::class.java,
            PhpUndefinedMethodInspection::class.java,
            PhpUndefinedClassConstantInspection::class.java,
            PhpIncompatibleReturnTypeInspection::class.java,
        )
        assertEmpty(findings())
        // The class's usages are PHP's: the clause is no reference to it.
        val klass = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).single { it.name == "nothrow" }
        assertFalse(myFixture.findUsages(klass).any { it.element?.let(PhaenonTokenTypes::isErased) == true })
    }

    fun testTheParsersLexerErasesTheClauseAlone() {
        val text = "function nothrow(): nothrow NoThrow => nothrow(); class C { const nothrow = 1; f(): int nothrow; g() nothrow {} h(): nothrow {} }"
        val lexer = PhaenonLexer()
        lexer.start(text)
        val seen = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenText.equals("nothrow", ignoreCase = true)) seen.add(lexer.tokenText + ":" + if (lexer.tokenType === PhaenonTokenTypes.ERASED) "erased" else lexer.tokenType.toString())
            lexer.advance()
        }
        val identifier = PhpTokenTypes.IDENTIFIER.toString()
        assertEquals(
            listOf(
                "nothrow:$identifier", "nothrow:$identifier", "NoThrow:erased", "nothrow:$identifier",
                "nothrow:$identifier", "nothrow:erased", "nothrow:erased", "nothrow:$identifier",
            ),
            seen,
        )
    }

    fun testQuickDocumentationShowsTheWord() {
        myFixture.configureByFile("d18/Nothrow.phn")

        val find = myFixture.hint("    find(int \$id): ?string nothrow {", 5).orEmpty()
        assertTrue(find, find.startsWith("find(int \$id): ") && find.endsWith("string nothrow"))
        assertEquals("all(): list<string> nothrow", myFixture.hint("    all(): list<string> nothrow =>", 5))
        assertEquals("function twice(int \$x): int nothrow", myFixture.hint("function twice", 10))
    }

    /**
     * PhpStorm reads a method without a docblock against its parent's `@throws` tags, and the
     * filter reads it against the parent's clause. A method that says `nothrow` compiles with a
     * docblock of its own, `@throws void`, which PhpStorm reads instead: no parent's clause answers
     * for it.
     */
    fun testAMethodThatSaysNothrowIsNotAnsweredForByItsParent() {
        myFixture.enableInspections(PhpUnhandledExceptionInspection::class.java)
        myFixture.configureByText(
            "U.phn",
            """
            namespace App;

            use App\Errors\NotFound;

            interface Finder {
                find(int ${'$'}id): string throws NotFound;

                get(int ${'$'}id): string throws NotFound;
            }

            final class U implements Finder {
                find(int ${'$'}id): string => throw new NotFound(${'$'}id);

                get(int ${'$'}id): string nothrow => throw new NotFound(${'$'}id);
            }
            """.trimIndent() + "\n",
        )

        assertEquals(listOf("Unhandled Errors\\NotFound @'throw'"), findings())
        val unhandled = myFixture.doHighlighting().single { it.description?.startsWith("Unhandled") == true }
        assertTrue(functions().getValue("U::get").textRange.contains(unhandled.startOffset))
    }

    /** An override inherits `nothrow` (design/functions.md §4.5): Implement Methods writes the parent's types and no clause. */
    fun testAnImplementedMethodTakesTheReturnTypeAndNoClause() {
        myFixture.configureByText(
            "Users.phn",
            """
            namespace App;

            final class User {}

            interface Finder<T of object>
            {
                find(int ${'$'}id): ?T nothrow;

                all(): list<T> nothrow;

                count(): int nothrow;
            }

            final class Users implements Finder<User>
            {
            <caret>}
            """.trimIndent() + "\n",
        )

        myFixture.performEditorAction("ImplementMethods")

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    find(int \$id): ?User {"))
        assertTrue(text, text.contains("    all(): list<User> {"))
        assertTrue(text, text.contains("    count(): int {"))
        assertEquals(text, 3, Regex("nothrow").findAll(text).count())
    }

    fun testReformatKeepsTheWordWhereItIs() {
        val source = File(testDataPath, "d18/Nothrow.phn").readText()
        val cases = mapOf(
            "as written" to source,
            "lost indentation" to source.lines().joinToString("\n") { it.trimStart() },
            "PSR-12 braces" to source.replace(" {\n", "\n{\n"),
        )
        for ((label, text) in cases) {
            val file = myFixture.configureByText("Layout.phn", text)
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
            assertEquals(label, source, myFixture.editor.document.text)
        }
    }

    fun testAPhpFileKeepsItsSyntaxError() {
        myFixture.configureByText("P.php", "<?php\nfunction f(): int nothrow { return 1; }\n")
        assertNotEmpty(errors())
    }
}
