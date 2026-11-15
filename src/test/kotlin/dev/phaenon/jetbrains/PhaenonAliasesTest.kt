package dev.phaenon.jetbrains

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassInspection
import com.jetbrains.php.lang.inspections.PhpUnusedAliasInspection
import com.jetbrains.php.lang.psi.elements.PhpUse

/**
 * A `type` alias is a name of its namespace (design/generics.md §11.6, §11.7): declared in one
 * file, named in another through `use`, a qualified name or, in its own namespace, bare. These
 * tests are over the files of `testData/shapes/shop`, the compiler's own named-shapes program and
 * a file that names its aliases every way: what the index holds of them, what PhpStorm reports
 * of their names, and where a name leads. [PhaenonAliasTypesTest] has what is typed behind one.
 */
class PhaenonAliasesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("shapes/shop", "src")
    }

    private fun open(path: String): PsiFile = myFixture.configureFromTempProjectFile(path)

    // ---------------------------------------------------------------- the index

    fun testEveryDeclarationIsIndexedUnderItsQualifiedName() {
        myFixture.addFileToProject("src/global.phn", "type Id = int|string;\n")
        myFixture.addFileToProject(
            "src/braced.phn",
            "namespace One {\n    type Pair<A, B of object = \\stdClass> = (A, B);\n}\n\nnamespace Two {\n    use One\\Pair;\n\n    type Both = Pair<int, self>;\n\n    final class Holder\n    {\n        hold(): void\n        {\n        }\n    }\n}\n",
        )
        // PHP names no alias (§11.7), and a `type` inside a function is no namespace's.
        myFixture.addFileToProject("src/plain.php", "<?php\nnamespace Shop\\Orders;\n\n/* type Fake = int; */\n")

        assertEquals("array{sku: string, qty: int}", find("\\Shop\\Orders\\Line")!!.type)
        assertEquals("array{id: int, lines: list<\\Shop\\Orders\\Line>, buyer?: \\Shop\\Orders\\Customer}", find("Shop\\Orders\\Order")!!.type)
        // Names are compared as class names are, without regard to case.
        assertEquals("\\Shop\\Orders\\Page", find("\\shop\\ORDERS\\page")!!.fqn)
        assertEquals(listOf("T"), find("\\Shop\\Orders\\Page")!!.parameters.map { it.name })
        assertEquals("int|string", find("\\Id")!!.type)
        val pair = find("\\One\\Pair")!!
        assertEquals("(A, B)", pair.type)
        assertEquals(listOf(PhaenonAliasIndex.Parameter("A", "", ""), PhaenonAliasIndex.Parameter("B", "object", "\\stdClass")), pair.parameters)
        assertEquals("\\One\\Pair<int, self>", find("\\Two\\Both")!!.type)
        assertNull(find("\\Shop\\Orders\\Fake"))
        assertNull(find("\\Shop\\Orders\\Customer"))
    }

    fun testAClassWinsOverAnAliasOfItsName() {
        myFixture.addFileToProject("src/Orders/Clash.phn", "namespace Shop\\Orders;\n\ntype Customer = array{name: string};\ntype Line = int;\n")

        // §11.7: the name is the class's, and the compiler reports the alias (P2511).
        assertNull(find("\\Shop\\Orders\\Customer"))
        // An alias declared twice is still one: the first the index gives.
        assertNotNull(find("\\Shop\\Orders\\Line"))
        assertEquals(2, PhaenonAliases.declarations(project, "\\Shop\\Orders\\Line").size)
    }

    private fun find(fqn: String): PhaenonAliasIndex.Declaration? = PhaenonAliases.find(project, fqn)

    // ---------------------------------------------------------------- findings

    fun testAnAliasIsNoUndefinedClassAndItsImportIsUsed() {
        myFixture.enableInspections(PhpUndefinedClassInspection::class.java, PhpUnusedAliasInspection::class.java)
        myFixture.addFileToProject(
            "src/Http/Wrong.phn",
            "namespace Shop\\Http;\n\nuse Shop\\Orders\\Line;\nuse Shop\\Orders\\Missing;\n\nfunction wrong(Missing \$missing, Line \$line): void {}\n",
        )
        // PHP names no alias (§11.7): in a `.php` file the name is a class nobody declared.
        myFixture.addFileToProject("src/Http/plain.php", "<?php\nnamespace Shop\\Http;\n\nuse Shop\\Orders\\Line;\n\nfunction plain(Line \$line): void {}\n")

        // In a signature, a docblock, a `use`, a group, under another name, through a namespace's import.
        for (path in listOf("src/Orders/types.phn", "src/Orders/Orders.phn", "src/Http/OrderController.phn", "src/Http/Uses.phn")) {
            open(path)
            assertEmpty(path, findings())
        }
        open("src/Http/Wrong.phn")
        assertEquals(listOf("Undefined class 'Missing': Missing", "Undefined class 'Missing': Missing"), findings())
        open("src/Http/plain.php")
        assertEquals(listOf("Undefined class 'Line': Line", "Undefined class 'Line': Line"), findings())
    }

    fun testOptimizeImportsKeepsTheImportOfAnAlias() {
        val file = open("src/Http/Uses.phn")
        val imports = PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java).map { it.fqn }

        OptimizeImportsProcessor(project, file).runWithoutProgress()

        assertEquals(imports.sorted(), PsiTreeUtil.findChildrenOfType(myFixture.file, PhpUse::class.java).map { it.fqn }.sorted())
    }

    /** What the two inspections say: an undefined class, an unused import. */
    private fun findings(): List<String> =
        myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && (it.description.orEmpty().startsWith("Undefined class") || it.description.orEmpty().startsWith("Import")) }
            .map { "${it.description}: ${it.text}" }

    // ---------------------------------------------------------------- navigation

    fun testANameLeadsToItsTypeDeclarationFromAnyFile() {
        val types = open("src/Orders/types.phn").text
        val declared = { name: String -> "types.phn" to types.indexOf("type $name") + "type ".length }

        // Bare in its own namespace: a signature, a docblock, a type argument (an erased construct).
        open("src/Orders/Orders.phn")
        assertEquals(listOf(declared("Order")), targets("find(int \$id): ?Order", "find(int \$id): ?".length))
        assertEquals(listOf(declared("Order")), targets("@var list<Order>", "@var list<".length))
        assertEquals(listOf(declared("Order")), targets("Page<Order>", "Page<".length))
        assertEquals(listOf(declared("Page")), targets("Page<Order>", 0))
        assertEquals(listOf(declared("Line")), targets("Line ...\$lines", 0))

        // Through `use`, from the import itself, under another name, in a group, through a namespace's import.
        open("src/Http/Uses.phn")
        assertEquals(listOf(declared("Page")), targets("use Shop\\Orders\\Page;", "use Shop\\Orders\\".length))
        assertEquals(listOf(declared("Page")), targets("private Page<Line> \$page", "private ".length))
        assertEquals(listOf(declared("Line")), targets("private Page<Line> \$page", "private Page<".length))
        assertEquals(listOf(declared("Order")), targets("private ?Placed \$last", "private ?".length))
        assertEquals(listOf(declared("Order")), targets("{Line, Order as Placed}", "{Line, ".length))
        assertEquals(listOf(declared("Line")), targets("{Line, Order as Placed}", 1))
        assertEquals(listOf(declared("Order")), targets("total(Orders\\Order \$order)", "total(Orders\\".length))
        assertEquals(listOf(declared("Line")), targets("@var list<Line>", "@var list<".length))

        // In another alias's shape, where a class name leads to its class as before.
        open("src/Orders/types.phn")
        assertEquals(listOf(declared("Line")), targets("lines: list<Line>", "lines: list<".length))
        assertEquals(listOf("Orders.phn" to myFixture.findFileInTempDir("src/Orders/Orders.phn").let { String(it.contentsToByteArray()).indexOf("class Customer") + "class ".length }), targets("buyer?: Customer", "buyer?: ".length))
        // A type parameter of the alias leads to its declaration in the same list.
        assertEquals(listOf("types.phn" to types.indexOf("Page<T>") + "Page<".length), targets("items: list<T>", "items: list<".length))
        // A key leads nowhere: it is no name.
        assertEmpty(targets("lines: list<Line>", 1))
    }

    private fun targets(needle: String, shift: Int): List<Pair<String, Int>> {
        val offset = myFixture.file.text.indexOf(needle).also { check(it >= 0) { needle } } + shift
        myFixture.editor.caretModel.moveToOffset(offset)
        return GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset).map { it.containingFile.name to it.textOffset }
    }

}
