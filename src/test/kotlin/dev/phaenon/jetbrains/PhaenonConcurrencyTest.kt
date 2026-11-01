package dev.phaenon.jetbrains

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference

/**
 * A `concurrently` block used as a statement parses as a block and `spawn expr` as its
 * expression (design/concurrency.md §11); members and functions called concurrently or spawn
 * are untouched. The editor highlighter colours the two words as keywords (a `concurrently(…)`
 * call cannot be told from a block by one token, and is coloured too).
 */
class PhaenonConcurrencyTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    private val source = """
        namespace App;

        use Amp\Cancellation;
        use Phaenon\Concurrent\Scope;

        final class Profiles
        {
            fun __construct(private readonly Users ${'$'}users)
            {
            }

            fun touchAll(list<int> ${'$'}ids, Cancellation ${'$'}stop): int
            {
                ${'$'}total = 0;
                concurrently (timeout: 5.0, cancellation: ${'$'}stop) {
                    ${'$'}tasks = [];
                    foreach (${'$'}ids as ${'$'}id) {
                        ${'$'}tasks[${'$'}id] = spawn ${'$'}this->users->touch(${'$'}id);
                    }
                    foreach (Scope::all(${'$'}tasks) as ${'$'}n) {
                        ${'$'}total += ${'$'}n;
                    }
                }
                concurrently {
                    spawn ${'$'}this->users->touch(1);
                }
                ${'$'}x = Api::concurrently([]);
                ${'$'}y = concurrently([]);

                return ${'$'}total;
            }
        }
    """.trimIndent()

    fun testAStatementBlockParsesAsABlockAndCallsStay() {
        myFixture.configureByText("Profiles.phn", source)

        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
        assertNotNull(PsiTreeUtil.findChildrenOfType(myFixture.file, MethodReference::class.java).singleOrNull { it.name == "concurrently" })
        assertNotNull(PsiTreeUtil.findChildrenOfType(myFixture.file, FunctionReference::class.java).singleOrNull { it.name == "concurrently" && it !is MethodReference })
        assertEquals(1, PsiTreeUtil.findChildrenOfType(myFixture.file, MethodReference::class.java).count { it.name == "all" })
    }

    fun testTheHighlightingLexerColoursTheKeywords() {
        val lexer = PhaenonHighlightingLexer(PhpWithOptionalOpenTagLexer())
        val text = "concurrently { \$t = spawn f(); } concurrently (timeout: 1) { } Api::concurrently(1); concurrently(1); \$o->spawn(); spawn = 1;"
        lexer.start(text)

        val seen = ArrayList<String>()
        while (lexer.tokenType != null) {
            val t = lexer.tokenText
            if (t.startsWith("concurrently") || t.startsWith("spawn")) seen.add(t.substringBefore(' ') + ":" + lexer.tokenType)
            lexer.advance()
        }

        assertEquals(
            listOf(
                "concurrently:" + PhpTokenTypes.kwFUNCTION, "spawn:" + PhpTokenTypes.kwFUNCTION, "concurrently:" + PhpTokenTypes.kwFUNCTION,
                "concurrently:" + PhpTokenTypes.IDENTIFIER, "concurrently:" + PhpTokenTypes.kwFUNCTION, "spawn:" + PhpTokenTypes.IDENTIFIER, "spawn:" + PhpTokenTypes.IDENTIFIER,
            ),
            seen,
        )
    }
}
