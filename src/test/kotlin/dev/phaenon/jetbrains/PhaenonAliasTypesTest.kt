package dev.phaenon.jetbrains

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpTypedElement

/**
 * What is typed behind a `type` alias (design/generics.md §11.6): a parameter, a property or a
 * return typed with one is what the alias stands for, so that a key read off it, a `foreach` over
 * one of its entries and a member behind an entry resolve as they do in the compiled PHP, whose
 * docblock spells the shape out. And what PhpStorm, to which the name is a class, says of the
 * values held against it. Over the files of `testData/shapes/shop`, as [PhaenonAliasesTest] is.
 */
class PhaenonAliasTypesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("shapes/shop", "src")
    }

    private fun open(path: String): PsiFile = myFixture.configureFromTempProjectFile(path)

    fun testADeclarationTypedWithAnAliasIsWhatTheAliasStandsFor() {
        open("src/Http/Uses.phn")

        // A parameter, by an import through the namespace: the shape's entries, a class behind one.
        assertEquals("\\array[]", access("\$order['lines']"))
        assertEquals("\\int", access("\$line['qty']"))
        assertEquals("\\Shop\\Orders\\Customer", access("\$order['buyer']"))
        val name = PsiTreeUtil.findChildrenOfType(myFixture.file, FieldReference::class.java).single { it.text == "\$order['buyer']->name" }
        assertEquals("name", (name.resolve() as com.jetbrains.php.lang.psi.elements.Field).name)
        // A property typed with a generic alias, and a parameter: the argument is where `T` stood.
        assertEquals("\\int", access("\$this->page['total']"))
        assertEquals("\\array", access("\$page['items'][0]"))
        assertEquals("\\int", access("\$first['id']"))
        // A method of another file returns one, and a closure takes one.
        assertEquals("\\int", access("\$order['id']"))
        assertEquals("\\string", access("\$line['sku']"))
    }

    fun testADocblocksAliasIsWhatItStandsFor() {
        open("src/Orders/Orders.phn")

        // `@var list<Order>` over `private array $orders`: the loop's value is an Order.
        assertEquals("\\int", access("\$order['id']"))
    }

    fun testAGenericAliasTakesItsArgumentsDefaultsAndBounds() {
        val file = myFixture.addFileToProject(
            "src/Model/generic.phn",
            "namespace Shop\\Model;\n\nuse Shop\\Orders\\Customer;\nuse Shop\\Orders\\Line;\n\n" +
                "type Box<T of object = Customer> = array{value: T, all: list<T>, pair: (T, int), 'a-b': map<string, T>, 7: ?T};\n" +
                "type Lines = list<Line>;\ntype Ping = list<Pong>;\ntype Pong = ?Ping;\n\n" +
                "function f(Box \$default, Box<Line> \$given, Lines \$lines, Ping \$ping, int \$k): void\n{\n" +
                "    \$default['value'];\n    \$given['value'];\n    \$given['all'][0];\n    \$given['pair'][0];\n    \$given['pair'][1];\n" +
                "    \$given['a-b']['x'];\n    \$given[7];\n    \$given['7'];\n    \$given[\$k];\n    \$given['missing'];\n    \$lines[0]['sku'];\n    \$ping[0];\n" +
                "    foreach (\$lines as \$each) {\n        \$each['qty'];\n    }\n}\n",
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        assertEquals("\\Shop\\Orders\\Customer", access("\$default['value']"))
        assertEquals("\\array", access("\$given['value']"))
        assertEquals("\\array", access("\$given['all'][0]"))
        assertEquals("\\array", access("\$given['pair'][0]"))
        assertEquals("\\int", access("\$given['pair'][1]"))
        assertEquals("\\array", access("\$given['a-b']['x']"))
        // An integer key, bare or quoted, is one key; a nullable entry is the type or null.
        assertContainsElements(access("\$given[7]").split('|'), "\\array", "\\null")
        assertContainsElements(access("\$given['7']").split('|'), "\\array", "\\null")
        assertEquals("\\string", access("\$lines[0]['sku']"))
        assertEquals("\\int", access("\$each['qty']"))
        // No such key: nothing is said. Any key: each entry's type.
        assertEquals("", access("\$given['missing']"))
        assertContainsElements(access("\$given[\$k]").split('|'), "\\array", "\\array[]", "\\null")
        // An alias that mentions itself (P2015) ends: no hang, and nothing known of what comes round.
        access("\$ping[0]")
    }

    /** The types of the array access [text], PhpStorm's `mixed` and its "unknown" aside, in order. */
    private fun access(text: String): String {
        val access = PsiTreeUtil.findChildrenOfType(myFixture.file, ArrayAccessExpression::class.java).first { it.text == text }
        return (access as PhpTypedElement).type.global(project).types.filter { it != "\\mixed" && it != "?" }.sorted().joinToString("|")
    }

    /** The inspections PhpStorm runs by default, all of them: what a project of named shapes shows on opening. */
    fun testAProjectOfNamedShapesReadsWithoutFalseFindings() {
        val tools = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.language == "PHP" && it.enabledByDefault }.map { it.instantiateTool() }
        myFixture.enableInspections(*tools.toTypedArray())

        for (path in listOf("src/Orders/types.phn", "src/Orders/Orders.phn", "src/Http/OrderController.phn", "src/Http/Uses.phn")) {
            open(path)
            val findings = myFixture.doHighlighting()
                .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
                // The test project has neither PHP's own stubs nor a PSR-4 root.
                .filter { it.inspectionToolId != "PhpUndefinedFunctionInspection" && it.inspectionToolId != "PhpIllegalPsrClassPathInspection" }
                .map { "${it.inspectionToolId ?: it.severity}: ${it.description} at '${it.text}'" }
            assertEmpty("$path: $findings", findings)
        }
    }

    fun testWhatIsNoAliasIsStillReported() {
        val tools = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.language == "PHP" && it.enabledByDefault }.map { it.instantiateTool() }
        myFixture.enableInspections(*tools.toTypedArray())
        myFixture.addFileToProject(
            "src/Http/Wrong.phn",
            "namespace Shop\\Http;\n\nuse Shop\\Orders\\Customer;\nuse Shop\\Orders\\Line;\nuse Shop\\Orders\\Missing;\n\n" +
                "final class Wrong\n{\n    private Customer \$buyer;\n\n    __construct(Missing \$missing, Line \$line)\n    {\n        \$this->buyer = [];\n    }\n\n" +
                "    buyer(): Customer\n    {\n        return ['name' => 'a'];\n    }\n}\n",
        )
        // PHP names no alias: in a `.php` file the name is a class nobody declared.
        myFixture.addFileToProject("src/Http/plain.php", "<?php\nnamespace Shop\\Http;\n\nuse Shop\\Orders\\Line;\n\nfunction plain(Line \$line): void {}\n")

        open("src/Http/Wrong.phn")
        val wrong = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }.map { "${it.inspectionToolId}: ${it.text}" }
        assertContainsElements(wrong, "PhpUndefinedClassInspection: Missing", "PhpFieldAssignmentTypeMismatchInspection: \$this->buyer = []", "PhpIncompatibleReturnTypeInspection: return ['name' => 'a'];")
        assertDoesntContain(wrong, "PhpUndefinedClassInspection: Line")

        open("src/Http/plain.php")
        val plain = myFixture.doHighlighting().filter { it.inspectionToolId == "PhpUndefinedClassInspection" }.map { it.text }
        assertEquals(listOf("Line", "Line"), plain)
    }

    // ---------------------------------------------------------------- the edges

    /** Every default inspection over the open file: what is reported as a warning or an error, the test project's noise aside. */
    private fun warnings(): List<String> {
        val tools = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.language == "PHP" && it.enabledByDefault }.map { it.instantiateTool() }
        myFixture.enableInspections(*tools.toTypedArray())
        return myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
            .filter { it.inspectionToolId !in NOISE }
            .map { "${it.inspectionToolId ?: it.severity}: ${it.description} at '${it.text}'" }
    }

    /** The test project has neither PHP's own stubs nor a PSR-4 root, and the fixtures read keys for their types alone. */
    private val NOISE = setOf("PhpUndefinedFunctionInspection", "PhpIllegalPsrClassPathInspection", "PhpExpressionResultUnusedInspection", "PhpUnusedLocalVariableInspection")

    private fun add(path: String, text: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject(path, text).virtualFile)
    }

    fun testAnArrowFunctionReturningAnAliasIsTypedAndRaisesNoFinding() {
        add(
            "src/Http/arrow.phn",
            "namespace Shop\\Http;\n\nuse Shop\\Orders\\{Line, Order};\n\nfinal class Arrow\n{\n" +
                "    run(Line \$one): void\n    {\n" +
                "        \$fn = fn (Line \$l): Order => ['id' => 1, 'lines' => [\$l]];\n" +
                "        \$made = \$fn(\$one);\n        \$made['id'];\n" +
                "        \$cl = function (Line \$l): Order {\n            return ['id' => 1, 'lines' => [\$l]];\n        };\n" +
                "        \$built = \$cl(\$one);\n        \$built['id'];\n" +
                "        \$rows = array_map(fn (int \$i): Line => ['sku' => 'x', 'qty' => \$i], [1, 2]);\n" +
                "        \$st = static fn (): ?Order => null;\n" +
                "        \$nested = fn (): callable => fn (): Line => ['sku' => 'y', 'qty' => 2];\n" +
                "        \$wrong = fn (): Arrow => ['sku' => 'z'];\n" +
                "    }\n\n    line(): Line => ['sku' => 'b', 'qty' => 3];\n}\n",
        )

        assertEquals("\\int", access("\$made['id']"))
        assertEquals("\\int", access("\$built['id']"))
        // A real class against an array is still a finding, in an arrow function as anywhere.
        assertEquals(listOf("['sku' => 'z']"), warnings().filter { it.startsWith("PhpIncompatibleReturnTypeInspection") }.map { it.substringAfter(" at '").removeSuffix("'") })
        assertEmpty(warnings().filter { !it.startsWith("PhpIncompatibleReturnTypeInspection") })
    }

    fun testAKeyThatWouldEndADocblockEndsNothing() {
        // The alias is spelled into a docblock for PhpStorm to read: a quoted key is written by its
        // author, and none of these may end the comment, start a tag in it or leave PHP.
        myFixture.addFileToProject(
            "src/Orders/odd.phn",
            "namespace Shop\\Orders;\n\ntype Mime = array{'*/*': int, 'text/html': string, '@x': int, '{@see Y}': int, '?>': int, 'two\nlines': int, '/** x': int, \"q'q\": int};\n",
        )
        add("src/Orders/mime.phn", "namespace Shop\\Orders;\n\nfinal class Mimes\n{\n    __construct(private Mime \$all) {}\n\n    of(Mime \$m): int\n    {\n        \$m['@x'];\n        \$m['two\nlines'];\n        \$this->all['text/html'];\n        \$m['text/html'];\n        return \$m['*/*'];\n    }\n}\n")

        // Read off a parameter, every key has its entry: the plugin looks it up in the alias's own text.
        assertEquals("\\int", access("\$m['*/*']"))
        assertEquals("\\string", access("\$m['text/html']"))
        assertEquals("\\int", access("\$m['@x']"))
        assertEquals("\\int", access("\$m['two\nlines']"))
        // Read off a property, PhpStorm looks the key up in what it was given: the keys that are safe to give.
        assertEquals("\\string", access("\$this->all['text/html']"))
        assertEmpty(warnings())
        assertEquals("array{'text/html': string, ...}", PhaenonType.parse("array{'*/*': int, 'text/html': string, '@x': int, '{@see Y}': int, '?>': int, 'two\nlines': int, '/** x': int, \"q'q\": int}")!!.docblock())
    }

    fun testAnOverrideIsJudgedByWhatAnAliasErasesTo() {
        myFixture.addFileToProject(
            "vendor/acme/Contract.php",
            "<?php\nnamespace Acme;\n\ninterface Arrayable\n{\n    /** @return array<string, mixed> */\n    public function toArray(): array;\n\n    public function fill(array \$data): void;\n\n    public function name(): string;\n}\n",
        )
        add(
            "src/Http/over.phn",
            "namespace Shop\\Http;\n\nuse Acme\\Arrayable;\nuse Shop\\Orders\\{Line, Order};\n\ntype Lines = list<Line>;\ntype Id = int|string;\n\n" +
                "interface Source\n{\n    get(int \$id): map<string, mixed>;\n\n    set(map<string, mixed> \$row): void;\n\n    all(): list<Line>;\n\n    one(): ?map<string, mixed>;\n\n    same(Order \$order): Order;\n\n    id(): Id;\n\n    count(): int;\n}\n\n" +
                "final class Impl implements Source, Arrayable\n{\n" +
                "    toArray(): Order => ['id' => 1, 'lines' => []];\n\n    fill(Order \$data): void {}\n\n" +
                "    get(int \$id): Order => ['id' => \$id, 'lines' => []];\n\n    set(Order \$row): void {}\n\n" +
                "    all(): Lines => [];\n\n    one(): ?Order => null;\n\n    same(Order \$order): Order => \$order;\n\n    id(): int => 1;\n\n" +
                // The two that are wrong: a shape where a string is returned, where an int is.
                "    name(): Order => ['id' => 1, 'lines' => []];\n\n    count(): Lines => [];\n}\n",
        )

        // The compiler writes `array` for each alias here, and so does the judgement: only the two real ones are left.
        val hierarchy = warnings().filter { it.contains("compatible") }.map { it.substringBefore(":") + " @" + it.substringAfter(" at '").removeSuffix("'") }
        assertEquals(listOf("PhaenonHierarchyChecks @Order", "PhaenonHierarchyChecks @Lines"), hierarchy)
    }

    fun testADefaultOfAnAliasIsNoError() {
        add(
            "src/Http/defaults.phn",
            "namespace Shop\\Http;\n\nuse Shop\\Orders\\{Customer, Line};\n\nfinal class Defaults\n{\n" +
                "    const Line DEFAULT = ['sku' => 'a', 'qty' => 1];\n\n    const list<Line> ALL = [];\n\n" +
                "    private Line \$line = ['sku' => 'b', 'qty' => 2];\n\n    private Customer \$buyer = [];\n\n" +
                "    sku(): string => \$this->line['sku'] . self::DEFAULT['sku'];\n}\n",
        )

        // A constant and a property typed with an alias take the array their shape is; a class takes none.
        val errors = warnings().filter { it.contains("default value") }
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().contains("Customer"))
        assertEquals("\\string", access("\$this->line['sku']"))
    }

    fun testAnExclusionOtherThanNullLeavesNoType() {
        add(
            "src/Http/narrow.phn",
            "namespace Shop\\Http;\n\nuse Shop\\Orders\\Order;\n\nfunction narrow(Order|string \$u, ?Order \$o): void\n{\n" +
                "    if (!is_array(\$u)) {\n        \$u['id'];\n    }\n    if (\$o !== null) {\n        \$o['lines'];\n    }\n}\n",
        )

        // What is left of `Order|string` without arrays is no Order: nothing is said of the key.
        assertEquals("", access("\$u['id']").replace("\\string", "").trim('|'))
        assertEquals("\\array[]", access("\$o['lines']"))
    }

    fun testALongChainOfAliasesIsBounded() {
        // Each alias names the next twice: what the first stands for doubles at every step.
        val chain = (0 until 24).joinToString("\n") { "type A$it = array{a: A${it + 1}, b: A${it + 1}};" } + "\ntype A24 = int;\n"
        myFixture.addFileToProject("src/Chain/chain.phn", "namespace Shop\\Chain;\n\n$chain")
        add("src/Chain/uses.phn", "namespace Shop\\Chain;\n\nfunction chained(A0 \$deep, A20 \$near): void\n{\n    \$deep['a']['a'];\n    \$near['a']['b']['a']['b'];\n}\n")

        val started = System.nanoTime()
        // Followed as far as the bound goes, and no further: the first is an array of arrays, the one near the end exact.
        assertEquals("\\array", access("\$deep['a']['a']"))
        assertEquals("\\int", access("\$near['a']['b']['a']['b']"))
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        assertTrue("$seconds s", seconds < 20)
    }
}
