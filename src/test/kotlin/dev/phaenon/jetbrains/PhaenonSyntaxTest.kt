package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.If
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.UnaryExpression
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * Phaenon syntax reaches PhpStorm's PHP parser through [PhaenonLexer] and [PhaenonPsiBuilder];
 * these tests check what comes out the other end: PHP PSI without errors, the erased text kept
 * as whitespace at its original offsets, and PHP left exactly as PhpStorm lexes it.
 */
class PhaenonSyntaxTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        // A Phaenon file is PHP 8.5 code; the test project would otherwise default to PHP 5.6.
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testEveryConstructParsesWithoutErrors() {
        val file = myFixture.configureByFile("syntax/Constructs.phn")

        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertEmpty("syntax errors: " + errors.map { "'${it.errorDescription}' at ${it.textOffset} near '${it.parent.text.take(40)}'" }, errors)
        PsiTestUtil.checkStubsMatchText(file)
    }

    fun testErasedSyntaxSurvivesAsWhitespaceAtItsOffsets() {
        val file = myFixture.configureByFile("syntax/Constructs.phn")
        val source = file.virtualFile.contentsToByteArray().toString(Charsets.UTF_8)

        assertEquals(source, file.text)
        val erased = PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java).filter { it.text.isNotBlank() }.map { it.text.trim() }
        assertContainsElements(
            erased,
            "type UserMap = map<Uuid, User>;",
            "<in TIn, out TOut>",
            "<T of object = object>",
            "map<string, list<Event<T>>>",
            "bytes",
            "class<T>",
            "(string, int)",
            "?list<T>",
            "(T, int): bool",
            ": list<(string, int)>",
            "(int): bool",
            "is list<int>",
            "as map<string, Money>",
            "as? Money",
            "<User>",
            "<Address>",
        )
        for (leaf in PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java)) {
            assertEquals("whitespace leaf text must map to the document", source.substring(leaf.textRange.startOffset, leaf.textRange.endOffset), leaf.text)
        }
    }

    fun testIsOnAClassNameIsInstanceof() {
        val file = myFixture.configureByText("Is.phn", "function f(mixed \$x): bool { return \$x is \\App\\User; }\n")

        val test = PsiTreeUtil.collectElementsOfType(file, BinaryExpression::class.java)
            .singleOrNull { it.node.elementType === PhpElementTypes.INSTANCEOF_EXPRESSION }
        assertNotNull("`\$x is User` should parse as an instanceof expression", test)
        assertEquals("\$x is \\App\\User", test!!.text)
        assertEquals(PhpTokenTypes.kwINSTANCEOF, test.operationType)
        assertEquals("\\App\\User", test.rightOperand?.text)
    }

    fun testBytesCastIsAStringCast() {
        val file = myFixture.configureByText("Cast.phn", "function f(string \$raw) { \$digest = (bytes) \$raw; }\n")

        val assignment = PsiTreeUtil.findChildOfType(file, AssignmentExpression::class.java)!!
        val cast = assignment.value as UnaryExpression
        assertEquals("(bytes) \$raw", cast.text)
        assertEquals(PhpTokenTypes.opSTRING_CAST, cast.operation?.node?.elementType)
        assertEquals("string", (cast as PhpTypedElement).type.toString())
    }

    fun testFunctionBodiesAreParsedEagerlyWithTheSameLexer() {
        val file = myFixture.configureByText(
            "Body.phn",
            "function f(mixed \$x): void {\n    if (\$x is list<int>) {\n        \$y = new Box<int>(1);\n        \$z = first<User>(\$y);\n    }\n}\n",
        )

        assertEmpty(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java))
        val lazy = PsiTreeUtil.collectElements(file) { it.node.elementType === PhpElementTypes.GROUP_STATEMENT }.toList()
        assertEmpty("a lazily parsed block would lose the erasures when expanded", lazy)
        val condition = PsiTreeUtil.findChildOfType(file, If::class.java)!!.condition
        assertInstanceOf(condition, Variable::class.java)
        assertEquals("\$x", condition!!.text)
    }

    fun testPhpWithoutPhaenonSyntaxLexesExactlyAsPhpStormDoes() {
        val php = """
            namespace App;

            use Foo\Bar as Baz;
            use function strlen as len;

            final class Plain
            {
                public const int MAX = 10;

                public function run(array ${'$'}items, ?Baz ${'$'}baz = null, int|string ... ${'$'}rest): static|null
                {
                    foreach (${'$'}items as ${'$'}key => ${'$'}item) {
                        if (${'$'}key < self::MAX && ${'$'}item > 2 || A < B && C > D) {
                            ${'$'}x = ${'$'}baz?->value <=> ${'$'}item;
                            ${'$'}y = (int) ${'$'}x >> 2;
                        }
                    }

                    return match (true) { ${'$'}x > ${'$'}y, ${'$'}y < 1 => ${'$'}this, default => null };
                }
            }
        """.trimIndent()

        assertEquals(tokens(PhpWithOptionalOpenTagLexer(), php), tokens(PhaenonLexer(), php))
    }

    fun testReformatLeavesErasedSyntaxInPlace() {
        val file = myFixture.configureByFile("syntax/Constructs.phn")
        val before = file.text

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }

        assertEquals(before, file.text)
    }

    fun testConstructsProduceNoErrorHighlighting() {
        myFixture.configureByFile("syntax/Constructs.phn")

        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        assertEmpty("error-level highlighting: " + errors.map { "${it.description} @${it.startOffset} '${it.text}'" }, errors)
    }

    private fun tokens(lexer: com.intellij.lexer.Lexer, text: String): List<Pair<IElementType, String>> {
        lexer.start(text)
        val out = ArrayList<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }
}
