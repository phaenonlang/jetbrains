package dev.phaenon.jetbrains

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.io.File

/**
 * Named shapes (design/collections.md §6): `array{id: int, name?: string}` in the right-hand side
 * of a `type` declaration, which [PhaenonLexer] erases whole, braces and all. These tests are the
 * lexer's: what is erased, and what a declaration that is still being typed does to the file
 * around it.
 */
class PhaenonShapesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testEveryFormOfAShapeIsErasedWithItsDeclaration() {
        val file = myFixture.configureByFile("shapes/Forms.phn")
        val source = File(testDataPath, "shapes/Forms.phn").readText()

        assertNoErrors(file)
        PsiTestUtil.checkStubsMatchText(file)
        assertEquals(source, file.text)
        // A declaration is one leaf from `type` to its `;`: no brace of a shape reaches the parser.
        val declared = Regex("""(?m)^(?:type|TYPE) (\w+)""").findAll(source).map { it.groupValues[1] }.toList()
        val declarations = erased(file).filter { Regex("""(?i)(^|\n)type\s""").containsMatchIn(it) }
        assertEquals(declared, declarations.map { Regex("""(?im)^type (\w+)""").find(it)!!.groupValues[1] })
        for (declaration in declarations) assertTrue(declaration, declaration.endsWith(";"))
        assertContainsElements(
            declarations,
            "type Row = array{id: int, name?: string};",
            "type Keys = array{class: string, list: int, fn: bool, default: int, 'a-b': int, '\$set'?: bool, \"x y\": int, '': int, 0: int, -1: string, '7': float};",
            "type Nested = ?array{pair: (int, array{x: int}), f: Closure(array{a: int}): array{b: int}, either: array{a: int}|array{b: int}|null};",
            "type Spaced = array { id : int , name ? : string , - 1 : int };",
            "TYPE Shouted = array{id: int};",
        )
        assertEquals(listOf("find", "page", "add"), PsiTreeUtil.findChildrenOfType(file, Method::class.java).map { it.name })
    }

    fun testADeclarationTakesItsDocblockAndTheCommentsBetween() {
        val file = myFixture.configureByFile("shapes/Forms.phn")

        val documented = erased(file).single { it.contains("type Documented") }
        assertTrue(documented, documented.startsWith("/**\n * A row as the table has it."))
        assertTrue(documented, documented.contains("// a note between the two\ntype Documented = array{id: int};"))
        // Left in the tree, the docblock would be the next declaration's, and so would its tags.
        val table = PsiTreeUtil.findChildOfType(file, PhpClass::class.java)!!
        assertNull(table.docComment)
        assertFalse(table.isDeprecated)
    }

    fun testADocblockStaysWithTheDeclarationItDocuments() {
        val file = myFixture.configureByText(
            "Kept.phn",
            "namespace App;\n\ntype Row = array{id: int};\n\n/** @deprecated use Rows */\nfinal class Table {}\n\n/** A type that is not one yet. */\ntype Half = array{id: int\n",
        )

        assertEquals(listOf("type Row = array{id: int};"), erased(file))
        assertTrue(PsiTreeUtil.findChildOfType(file, PhpClass::class.java)!!.isDeprecated)
    }

    fun testAShapeIsSyntaxOnlyInATypeDeclaration() {
        // In a return type the brace opens the body, as in PHP (collections.md §6.1): the lexer
        // erases nothing there, and the compiler's message is the one to read.
        val file = myFixture.configureByText(
            "Inline.phn",
            "namespace App;\n\nfunction row(array{id: int} \$row): array{id: int}\n{\n    return \$row;\n}\n\ntype Pair<T of array{a: int}> = list<T>;\n",
        )

        assertEmpty(erased(file).filter { it.contains("{") })
        assertNotEmpty(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java))
    }

    /**
     * A declaration while it is typed, with the file's next declaration below it: the scan gives
     * the declaration up where it stops fitting, the parser reads what is there as PHP, and the
     * class below is still a class with its method. Nothing is erased beyond the declaration.
     */
    fun testAHalfTypedShapeLeavesTheRestOfTheFileToTheParser() {
        val heads = listOf(
            "type Row = array",
            "type Row = array{",
            "type Row = array{}",
            "type Row = array{id",
            "type Row = array{id}",
            "type Row = array{id:",
            "type Row = array{id: }",
            "type Row = array{id: int",
            "type Row = array{id: int,",
            "type Row = array{id: int, }",
            "type Row = array{id: int, name?",
            "type Row = array{id: int, name?: ",
            "type Row = array{id: int, name?: string}",
            "type Row = array{id: int, ...",
            "type Row = array{id: int, ..., name: string};",
            "type Row = array{-",
            "type Row = array{- : int};",
            "type Row = array{1.5: int};",
            "type Row = array{a-b: int};",
            "type Row = array{int, string};",
            "type Row = array{id: int; name: string};",
            "type Row = array{id: array{x: int};",
            "type Row = array{id: list<array{x: int}};",
            "type Row = array{id: list<array{x: int>};",
            "type Row = array{id: Closure(array{x: int): int};",
            "type Row<T of array{a: int}> = array{id: T};",
            "type = array{id: int};",
            "/** A row. */\ntype Row = array{id: int",
        )
        val rest = "\n\nfinal class Table\n{\n    /** @var list<Row> */\n    private array \$rows = [];\n\n    find(int \$id): ?Row => \$this->rows[\$id] ?? null;\n}\n"
        for (head in heads) {
            val what = "`${head.replace("\n", "\\n")}`"
            val text = "namespace App;\n\n$head$rest"
            val file = lexedAndParsed(text, what)
            assertEmpty("$what: nothing of an unfinished declaration is erased", erased(file).filter { it.contains("Row") && !it.contains("list<Row>") })
            val table = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).singleOrNull { it.name == "Table" }
            assertNotNull("$what: the class below is still a class", table)
            assertEquals("$what: with its method", listOf("find"), table!!.ownMethods.map { it.name })
        }
    }

    /** A key's quote left open is PHP's string to the end of the file, in a shape as anywhere: lexed and parsed, no more. */
    fun testAnOpenQuoteInAShapeIsAnOpenString() {
        for (head in listOf("type Row = array{'id", "type Row = array{'id': int, \"na", "type Row = array{\"a\\n\": int};")) {
            lexedAndParsed("namespace App;\n\n$head\n\nfinal class Table {}\n", "`$head`")
        }
    }

    /** Every beginning of the fixture is a file: cut at each character, it lexes into tokens that cover it and parses. */
    fun testEveryBeginningOfTheFormsLexesAndParses() {
        val text = File(testDataPath, "shapes/Forms.phn").readText()
        for (end in 0..text.length) lexedAndParsed(text.substring(0, end), "shapes/Forms.phn cut at $end")
    }

    /**
     * The scan is one pass, and it has an end. A shape too long to be one and types nested
     * deeper than any stack are lexed like the rest: every token read a bounded number of times,
     * the nesting given up at a depth no written type has.
     */
    fun testALongOrDeepDeclarationIsLexedInOnePass() {
        val entries = 40_000
        val long = "type Row = array{" + (0 until entries).joinToString(", ") { "k$it?: list<map<string, array{id: int, f: Closure(int): bool}>>" }
        val started = System.nanoTime()
        val closed = tokens("$long};")
        val open = tokens("$long, ")
        val deep = tokens("type Deep = " + "array{a: list<".repeat(20_000))
        val seconds = (System.nanoTime() - started) / 1_000_000_000

        assertEquals("a declaration that scans is one token", listOf(PhaenonTokenTypes.ERASED), closed.map { it.first })
        assertFalse(open.any { it.first === PhaenonTokenTypes.ERASED && it.second.contains("{") })
        assertFalse(deep.any { it.first === PhaenonTokenTypes.ERASED && it.second.contains("{") })
        // Under a second on a laptop; a scan that started over at each token would take hours.
        assertTrue("2.5 million tokens took $seconds s", seconds < 60)
    }

    private fun erased(file: PsiFile): List<String> =
        PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }

    private fun assertNoErrors(file: PsiFile) {
        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertEmpty("syntax errors: " + errors.map { "'${it.errorDescription}' at ${it.textOffset} near '${it.parent.text.take(40)}'" }, errors)
    }

    private fun lexedAndParsed(text: String, what: String): PsiFile {
        val tokens = try {
            tokens(text)
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
        return file
    }

    private fun tokens(text: String): List<Pair<IElementType, String>> {
        val lexer = PhaenonLexer()
        lexer.start(text)
        val out = ArrayList<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }
}
