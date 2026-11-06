package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import com.jetbrains.php.lang.inspections.PhpExpressionResultUnusedInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedVariableInspection
import com.jetbrains.php.lang.inspections.PhpUnusedParameterInspection
import com.jetbrains.php.lang.inspections.controlFlow.PhpUnusedLocalVariableInspection
import com.jetbrains.php.lang.inspections.type.PhpIncompatibleReturnTypeInspection
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * `concurrently` and `spawn` (design/concurrency.md §4, §6, §11). A block used as a statement
 * parses as a block; a block used as a value, `spawn { … }` and `spawn expr` parse as the closures
 * they compile to ([PhaenonLexer], [PhaenonBlocks]), with the findings PhpStorm draws from that
 * closure's scope filtered and the values typed (`testData/concurrency/Blocks.phn`, a fan-out
 * over names among others). Members and functions called concurrently or spawn are untouched. The
 * editor highlighter colours the two words as keywords (a `concurrently(…)` call cannot be told
 * from a block by one token, and is coloured too).
 */
class PhaenonConcurrencyTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    private fun configureBlocks() {
        // PhpStorm's own stub of `\Exception` and friends, from the PHP plugin: the test project has none.
        PhpLanguage::class.java.classLoader.getResource("stubs/Core/Core_c.php")?.readText()?.let { myFixture.addFileToProject("stubs/Core_c.php", it) }
        myFixture.copyFileToProject("concurrency/stubs/Future.php")
        myFixture.copyFileToProject("concurrency/stubs/Scope.php")
        myFixture.configureByFiles("concurrency/Blocks.phn")
    }

    private fun enableScopeInspections() = myFixture.enableInspections(
        PhpUndefinedVariableInspection::class.java,
        PhpUnusedParameterInspection::class.java,
        PhpUnusedLocalVariableInspection::class.java,
        PhpIncompatibleReturnTypeInspection::class.java,
        PhpExpressionResultUnusedInspection::class.java,
        PhpUndefinedMethodInspection::class.java,
    )

    private fun findings(): List<String> = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} @'${it.text}'" }

    fun testExpressionBlocksAndSpawnsParseAsClosures() {
        configureBlocks()

        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
        val kinds = PsiTreeUtil.findChildrenOfType(myFixture.file, Function::class.java).mapNotNull { PhaenonBlocks.kind(it) }
        // Three blocks used as values, three spawns; the statement block is a block.
        assertEquals(listOf("CONCURRENTLY", "SPAWN", "CONCURRENTLY", "SPAWN", "SPAWN", "CONCURRENTLY", "SPAWN"), kinds.map { it.name })
    }

    fun testTheEnclosingScopeIsTheBlocksScope() {
        enableScopeInspections()
        configureBlocks()

        // `$names`, `$org`, `$id`, `$second` read in blocks, `$name` in a task inside a block; parameters
        // used only there; `return` in a block, not the method's; a spawn statement's closure.
        assertEmpty(findings())
    }

    fun testAnUndefinedVariableInABlockIsStillReported() {
        enableScopeInspections()
        myFixture.configureByText(
            "Typo.phn",
            """
            namespace App;

            fun count(list<string> ${'$'}names): int
            {
                ${'$'}n = concurrently {
                    return \count(${'$'}nmes);
                };

                return ${'$'}n + \count(${'$'}names);
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Undefined variable '\$nmes' @'\$nmes'"), findings())
    }

    fun testABlockIsItsBodysValueAndASpawnAFuture() {
        configureBlocks()

        val types = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java)
            .filter { (it.parent as? AssignmentExpression)?.variable === it }
            .associate { it.name to it.type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted() }
        assertTrue("the block's value: what Scope::all returns ${types["exists"]}", "\\array" in types["exists"]!!)
        assertTrue("spawn expr: a Future of the call ${types["user"]}", "\\Amp\\Future<\\App\\User>" in types["user"]!!)
        assertEquals("await() through the Future", listOf("\\App\\User", "\\mixed"), types["found"]!!.filterNot { it.startsWith("#") })
        val tasks = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java).first { it.name == "tasks" && it.parent is ParameterList }
        assertTrue("spawn { } into an element: an array of Futures", "\\Amp\\Future<bool>[]" in tasks.type.global(project).typesWithParametrisedParts)
    }

    fun testReformatCodeLeavesTheBlocksAsWritten() {
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        configureBlocks()
        val before = myFixture.file.text
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals(before, myFixture.editor.document.text)

        // From lost indentation, every block's body comes back one level in, statement blocks too.
        val flat = before.lines().joinToString("\n") { it.trimStart() }
        val file = myFixture.configureByText("Flat.phn", flat)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        assertEquals(before, myFixture.editor.document.text)
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
