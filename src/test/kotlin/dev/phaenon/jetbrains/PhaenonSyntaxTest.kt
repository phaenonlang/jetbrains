package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.If
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.UnaryExpression
import java.io.File

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

    fun testErasedSyntaxSurvivesAsErasedLeavesAtItsOffsets() {
        val file = myFixture.configureByFile("syntax/Constructs.phn")
        val source = file.virtualFile.contentsToByteArray().toString(Charsets.UTF_8)

        assertEquals(source, file.text)
        val leaves = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }
        val erased = leaves.map { it.text.trim() }
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
            "Closure(T, int): bool",
            "(T, int): bool",
            ": list<(string, int)>",
            ": Closure(int): bool",
            "Closure(string, int=): void",
            ": Closure(string, string...): (int, string)",
            "<User>",
            "<Address>",
        )
        for (leaf in leaves) {
            assertEquals("an erased leaf's text must map to the document", source.substring(leaf.textRange.startOffset, leaf.textRange.endOffset), leaf.text)
            assertEquals("an erased leaf carries its own text, not the whitespace around it", leaf.text.trim(), leaf.text)
        }
        assertEmpty(PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java).filter { it.text.isNotBlank() })
    }

    fun testIsAndAsAreReadAsPhpReadsThem() {
        // D26 withdrew the operators: a type test is PHP's own, `is` is a name like any other, and
        // a test left over is the syntax error PHP makes of it, in `.phn` and `.php` alike (the
        // compiler's P1001 names the replacement, through `phn lsp`).
        val source = "function is(mixed \$x): bool { return \$x instanceof \\App\\User; }\n\n" +
            "function f(mixed \$x): bool { return is(\$x) && \$x is \\App\\User; }\n\n" +
            "function g(mixed \$x): mixed { return \$x as? \\App\\User; }\n"
        val phn = myFixture.configureByText("Is.phn", source)
        val php = myFixture.configureByText("Is.php", "<?php\n$source")

        assertEmpty(PsiTreeUtil.collectElementsOfType(phn, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) })
        val phnErrors = PsiTreeUtil.collectElementsOfType(phn, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset }
        val phpErrors = PsiTreeUtil.collectElementsOfType(php, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset - "<?php\n".length }
        assertNotEmpty(phnErrors)
        assertEquals(phpErrors, phnErrors)
        val test = PsiTreeUtil.collectElementsOfType(phn, BinaryExpression::class.java)
            .single { it.node.elementType === PhpElementTypes.INSTANCEOF_EXPRESSION }
        assertEquals(PhpTokenTypes.kwINSTANCEOF, test.operationType)
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
            "function f(mixed \$x): void {\n    if (\$x) {\n        \$y = new Box<int>(1);\n        \$z = first<User>(\$y);\n    }\n}\n",
        )

        assertEmpty(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java))
        val lazy = PsiTreeUtil.collectElements(file) { it.node.elementType === PhpElementTypes.GROUP_STATEMENT }.toList()
        assertEmpty("a lazily parsed block would lose the erasures when expanded", lazy)
        val branch = PsiTreeUtil.findChildOfType(file, If::class.java)!!.statement!!
        val erased = PsiTreeUtil.collectElementsOfType(branch, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }
        assertEquals(listOf("<int>", "<User>"), erased)
    }

    fun testKeywordNamedMethodsTakeTypeParameters() {
        // PHP lets a method carry a reserved word as its name, `function for<U>` among them.
        val file = myFixture.configureByText(
            "Keyword.phn",
            "final class Scenario<T>\n{\n    public static function for<U of Aggregate>(class<U> \$class, ?Id \$id = null): self<U>\n    {\n        return new self(\$class, \$id);\n    }\n\n    public function list<U>(list<U> \$xs): static { return \$this; }\n\n    public function run(): void\n    {\n        \$a = Scenario::for<Order>(Order::class);\n        \$b = \$this->list<int>([1]);\n        \$c = \$a?->list<int>([1]);\n    }\n}\n",
        )

        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertEmpty("syntax errors: " + errors.map { "'${it.errorDescription}' at ${it.textOffset} near '${it.parent.text.take(40)}'" }, errors)
        val names = PsiTreeUtil.collectElementsOfType(file, com.jetbrains.php.lang.psi.elements.Method::class.java).map { it.name }
        assertEquals(listOf("for", "list", "run"), names)
        val erased = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text.trim() }
        assertContainsElements(erased, "<U of Aggregate>", "<Order>", "<int>")
    }

    fun testPropertyHooksKeepTheirErasedTypes() {
        val file = myFixture.configureByText(
            "Hooks.phn",
            "final class User\n{\n    public list<string> \$roles {\n        get => array_unique([\$this->role, ...\$this->extra]);\n    }\n\n    public map<string, int> \$counts {\n        get {\n            return is_array(\$this->counts) ? \$this->counts : [];\n        }\n        set(map<string, int> \$value) => \$value;\n    }\n\n    public string \$name { get => \$this->first . ' ' . \$this->last; }\n}\n",
        )

        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertEmpty("syntax errors: " + errors.map { "'${it.errorDescription}' at ${it.textOffset} near '${it.parent.text.take(40)}'" }, errors)
        val erased = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text.trim() }
        assertContainsElements(erased, "list<string>", "map<string, int>")
    }

    fun testPhpSyntaxUnknownToTheIdeIsReportedAsInPhpFiles() {
        // The plugin erases Phaenon, not PHP: what PhpStorm's parser cannot read at its highest
        // language level (the PHP 8.5 pipe operator below PhpStorm 2025.3; nothing of PHP 8.5 from
        // the floor on) is an error in `.phn` and `.php` alike, and neither more nor less so.
        val phn = myFixture.configureByText("Pipe.phn", "function f(array \$xs): array { return \$xs |> array_values(...); }\n")
        val php = myFixture.configureByText("Pipe.php", "<?php\nfunction f(array \$xs): array { return \$xs |> array_values(...); }\n")

        val phnErrors = PsiTreeUtil.collectElementsOfType(phn, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset }
        val phpErrors = PsiTreeUtil.collectElementsOfType(php, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset - "<?php\n".length }
        assertEquals(phpErrors, phnErrors)
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

    /**
     * A file that ends where a construct has only begun: what is on screen while a call, a type
     * list or a return type is typed at the end of a file. The lexer looks ahead for what Phaenon
     * adds, and there the tokens it looks for are not there: an opening parenthesis (a `(bytes)`
     * cast?), a `<` (type arguments, their variance, an `of` bound?), a return type (a `throws`
     * behind it?). Each text lexes into tokens that cover it and parses into a file of that text.
     */
    fun testAFileEndingInAnUnfinishedConstructLexesAndParses() {
        val texts = listOf(
            "f(",
            "f(\n    ",
            "\$this->stream(",
            "parent::__construct(sprintf(\n        ",
            "final class C\n{\n    __construct(\n        ",
            "final class C\n{\n    static fromString(",
            "new Box<",
            "new Box<int",
            "new Box<int, ",
            "class C<",
            "class C<in ",
            "class C<T",
            "class C<T of ",
            "class C<T of object = ",
            "interface Stream extends \\Traversable<",
            "function first<",
            "\$f = fn<",
            "type Pair<",
            "type Pair<A",
            "final class C\n{\n    get(class<Event",
            "final class C\n{\n    __construct(public map<string, m",
            "final class C\n{\n    all(): list<",
            "final class C\n{\n    all(): list<int",
            "final class C<T>\n{\n    id(): T",
            "final class C<T>\n{\n    final id(): T ",
            "final class C\n{\n    all(): list<int> ",
            "final class C\n{\n    load(): bytes /* soon */ ",
            "function f(Closure(int",
            "function f(Closure(int, ",
            "function f(Closure(int): ",
            "function f(?",
            "function f((A, ",
            "function f(): ",
            "function f(): ?",
            "function f(): A|",
            "function f(): int throws ",
            "function f(): int throws A|",
            "function f(): int =>",
            "final class C\n{\n    &",
            "final class C\n{\n    name<",
            "f(?",
            "f(1, ?",
        )
        for (text in texts) assertLexesAndParses(text, "`${text.replace("\n", "\\n")}`")
    }

    /**
     * Every beginning of a file is a file: the text of three fixtures cut at each of its
     * characters, as it stands while it is typed from the top, lexes into tokens that cover it and
     * parses into a file of that text, without an exception.
     */
    fun testEveryBeginningOfAFileLexesAndParses() {
        for (name in listOf("syntax/Constructs.phn", "d18/Ends.phn", "methods/Shapes.phn")) {
            val text = File(testDataPath, name).readText()
            for (end in 0..text.length) assertLexesAndParses(text.substring(0, end), "$name cut at $end")
        }
    }

    private fun assertLexesAndParses(text: String, what: String) {
        val tokens = try {
            tokens(PhaenonLexer(), text)
        } catch (e: RuntimeException) {
            throw AssertionError("$what: the lexer threw $e", e)
        }
        assertEquals("$what: the tokens cover the text", text, tokens.joinToString("") { it.second })
        val file = try {
            PsiFileFactory.getInstance(project).createFileFromText("Cut.phn", PhpFileType.INSTANCE, text).also { it.node.firstChildNode }
        } catch (e: RuntimeException) {
            throw AssertionError("$what: the parse threw $e", e)
        }
        assertSame(what, PhaenonFileElementType, file.node.elementType)
        assertEquals(what, text, file.text)
    }

    fun testReformatLeavesErasedSyntaxAsWritten() {
        val file = myFixture.configureByFile("syntax/Constructs.phn")
        val erased = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }

        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(file)
        }

        // The formatter runs (PhaenonFormattingTest has what it does); the constructs come out as written.
        assertEquals(erased, PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text })
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java))
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
