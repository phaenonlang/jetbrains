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
}
