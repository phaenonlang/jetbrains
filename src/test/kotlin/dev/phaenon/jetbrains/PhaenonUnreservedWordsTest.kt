package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpExpressionResultUnusedInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedClassConstantInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedFunctionInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedVariableInspection
import com.jetbrains.php.lang.inspections.type.PhpIncompatibleReturnTypeInspection
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * `concurrently` and `spawn` are names like any other (roadmap D25, design/concurrency.md §17: the
 * syntax left the language and the words were never reserved). A function, a method or a constant
 * of either name is PHP code: both lexers hand the words over as PHP lexes them, and PhpStorm
 * parses, resolves, types and checks the declarations and their calls as in a `.php` file.
 */
class PhaenonUnreservedWordsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    private val source = """
        namespace App;

        final class Task
        {
            __construct(public readonly int ${'$'}value) {}
        }

        final class Pool
        {
            public const spawn = 2;

            spawn(int ${'$'}value): Task
            {
                return new Task(${'$'}value);
            }

            static concurrently(Task ...${'$'}tasks): int
            {
                ${'$'}sum = 0;
                foreach (${'$'}tasks as ${'$'}task) {
                    ${'$'}sum += ${'$'}task->value;
                }

                return ${'$'}sum;
            }
        }

        function spawn(int ${'$'}value): Task
        {
            return new Task(${'$'}value);
        }

        function concurrently(Task ...${'$'}tasks): int
        {
            return Pool::concurrently(...${'$'}tasks);
        }

        function run(Pool ${'$'}pool): int
        {
            ${'$'}first = spawn(1);
            ${'$'}second = ${'$'}pool->spawn(2);
            ${'$'}spawn = Pool::spawn;
            spawn(${'$'}spawn);

            return concurrently(${'$'}first, ${'$'}second) + ${'$'}spawn;
        }
    """.trimIndent()

    fun testFunctionsAndMethodsOfEitherNameParseAndResolve() {
        val file = myFixture.configureByText("Pool.phn", source)

        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).map { it.errorDescription })
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text })
        val functions = PsiTreeUtil.findChildrenOfType(file, Function::class.java).filter { it !is Method && !it.isClosure }.map { it.fqn }
        assertEquals(listOf("\\App\\spawn", "\\App\\concurrently", "\\App\\run"), functions)

        val calls = PsiTreeUtil.findChildrenOfType(file, FunctionReference::class.java)
            .filter { it.name == "spawn" || it.name == "concurrently" }
            .map { call -> call.text.substringBefore('(') + " → " + (call.resolve() as? Function)?.let { if (it is Method) it.containingClass?.name + "::" + it.name else it.fqn } }
        assertEquals(
            listOf("Pool::concurrently → Pool::concurrently", "spawn → \\App\\spawn", "\$pool->spawn → Pool::spawn", "spawn → \\App\\spawn", "concurrently → \\App\\concurrently"),
            calls,
        )
        assertNotNull(PsiTreeUtil.findChildrenOfType(file, ClassConstantReference::class.java).single { it.name == "spawn" }.resolve())
        assertEquals(1, PsiTreeUtil.findChildrenOfType(file, MethodReference::class.java).count { it.name == "spawn" })
    }

    fun testTheCallsAreTypedAsTheFunctionsTheyCall() {
        myFixture.configureByText("Pool.phn", source)

        val types = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java)
            .filter { (it.parent as? AssignmentExpression)?.variable === it }
            .associate { it.name to it.type.global(project).types.filter { t -> t != "?" }.sorted() }
        assertEquals(listOf("\\App\\Task"), types["first"])
        assertEquals(listOf("\\App\\Task"), types["second"])
    }

    fun testNothingIsReported() {
        myFixture.enableInspections(
            PhpUndefinedFunctionInspection::class.java,
            PhpUndefinedMethodInspection::class.java,
            PhpUndefinedClassConstantInspection::class.java,
            PhpUndefinedVariableInspection::class.java,
            PhpIncompatibleReturnTypeInspection::class.java,
            PhpExpressionResultUnusedInspection::class.java,
        )
        myFixture.configureByText("Pool.phn", source)

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
            .map { "${it.description} @'${it.text}'" }
        assertEmpty(findings)
    }

    fun testTheRemovedBlockIsNoLongerSyntax() {
        // What was a block used as a value is now a name followed by braces: an error to PHP, and so
        // to PhpStorm, as it is to the compiler once the syntax has left it.
        val file = myFixture.configureByText("Block.phn", "namespace App;\n\nfunction f(): int\n{\n    \$x = concurrently {\n        return 1;\n    };\n\n    return \$x;\n}\n")

        assertNotEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, Function::class.java).filter { it.isClosure })
    }

    fun testBothLexersHandTheWordsOverAsIdentifiers() {
        val text = "concurrently { \$t = spawn f(); } \$x = concurrently (timeout: 1) { }; spawn { }; Api::concurrently(1); concurrently(1); \$o->spawn(); spawn(1); spawn = 1;"
        // The editor's lexer is PhpStorm's own (PhaenonBaseLexerProvider), the parser's is PhaenonLexer.
        for (lexer in listOf(PhpWithOptionalOpenTagLexer(), PhaenonLexer())) {
            lexer.start(text)
            val seen = ArrayList<String>()
            while (lexer.tokenType != null) {
                val t = lexer.tokenText
                if (t == "concurrently" || t == "spawn") seen.add(t + ":" + lexer.tokenType)
                assertNotSame("nothing is erased", PhaenonTokenTypes.ERASED, lexer.tokenType)
                lexer.advance()
            }

            // Nine words, each an identifier: a constant, a function or a member name to the parser.
            assertEquals(lexer.javaClass.simpleName, List(9) { PhpTokenTypes.IDENTIFIER.toString() }, seen.map { it.substringAfter(':') })
        }
    }
}
