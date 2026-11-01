package dev.phaenon.jetbrains

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * `fun` declares a function or method with `public` implied, and a closure in a constant
 * expression is what the compiler lowers to PHP's `static function` (design/functions.md).
 */
class PhaenonFunTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    private val source = """
        namespace App;

        #[\Attribute(\Attribute::TARGET_CLASS)]
        final class Map
        {
            fun __construct(public readonly \Closure ${'$'}f)
            {
            }
        }

        #[Map(fn (int ${'$'}x): int => ${'$'}x * 2)]
        final class Box
        {
            const \Closure UPPER = fn (string ${'$'}s): string => \strtoupper(${'$'}s);

            private static ?\Closure ${'$'}lower = fn (string ${'$'}s): string => \strtolower(${'$'}s);

            private(set) int ${'$'}count = 0;

            fun __construct(private(set) string ${'$'}id, private \Closure ${'$'}normalise = fn (string ${'$'}s): string => \trim(${'$'}s))
            {
            }

            fun id(): string
            {
                return ${'$'}this->id;
            }

            protected static fun make<T>(T ${'$'}x): T
            {
                return ${'$'}x;
            }

            final fun &ref(array &${'$'}a): array
            {
                return ${'$'}a;
            }

            function plain(): void
            {
            }
        }

        fun helper(): int
        {
            return 1;
        }
    """.trimIndent()

    fun testFunDeclaresAMethodThatIsPublicUnlessWritten() {
        myFixture.configureByText("Box.phn", source)

        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
        val box = PsiTreeUtil.findChildrenOfType(myFixture.file, PhpClass::class.java).single { it.name == "Box" }
        val methods = box.ownMethods.associateBy { it.name }
        assertTrue(methods.getValue("id").access.isPublic)
        assertTrue(methods.getValue("make").access.isProtected && methods.getValue("make").isStatic)
        assertTrue(methods.getValue("ref").access.isPublic && methods.getValue("ref").isFinal)
        assertEquals("public", methods.getValue("__construct").access.toString())
        assertNotNull(PsiTreeUtil.findChildrenOfType(myFixture.file, com.jetbrains.php.lang.psi.elements.Function::class.java).singleOrNull { it.name == "helper" && it !is Method })
    }

    fun testFunMethodsAndAsymmetricPropertiesAreNotMissingVisibility() {
        val tools = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.shortName.orEmpty().contains("Visibility") || it.displayName.orEmpty().contains("visibility", ignoreCase = true) }
        myFixture.enableInspections(*tools.map { it.instantiateTool() }.toTypedArray())
        myFixture.configureByText("Box.phn", source)

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && it.description.orEmpty().contains("visibility", ignoreCase = true) }
            .map { myFixture.editor.document.getLineNumber(it.startOffset) + 1 }

        assertEquals(listOf(myFixture.editor.document.text.lines().indexOfFirst { it.contains("function plain") } + 1), findings)
    }

    fun testAClosureInAConstantExpressionIsNotAnError() {
        myFixture.configureByText("Box.phn", source)

        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.text to it.description }

        assertEmpty(errors)
    }

    fun testFunCompletesWhereADeclarationMayStart() {
        myFixture.configureByText("Completing.phn", "namespace App;\n\nfinal class A\n{\n    protected fu<caret>\n}\n")

        val lookup = myFixture.completeBasic()

        if (lookup == null) {
            assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("protected fun "))
        } else {
            assertContainsElements(myFixture.lookupElementStrings!!, "fun")
        }
    }

    fun testTheHighlightingLexerReadsFunAsAKeyword() {
        val lexer = PhaenonHighlightingLexer(PhpWithOptionalOpenTagLexer())
        val text = "fun a() {} fun &b() {} fun(); fun = 1;"
        lexer.start(text)

        val types = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenText == "fun") types.add(lexer.tokenType.toString())
            lexer.advance()
        }

        assertEquals(listOf(PhpTokenTypes.kwFUNCTION, PhpTokenTypes.kwFUNCTION, PhpTokenTypes.IDENTIFIER, PhpTokenTypes.IDENTIFIER).map { it.toString() }, types)
    }
}
