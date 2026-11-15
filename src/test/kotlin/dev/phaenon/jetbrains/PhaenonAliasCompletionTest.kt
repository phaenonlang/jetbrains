package dev.phaenon.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade

/**
 * `type` aliases in completion (design/generics.md §11.6): offered where a type is written, among
 * the classes, and inserted with their `use`. Over the files of `testData/shapes/shop`.
 */
class PhaenonAliasCompletionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("shapes/shop", "src")
    }

    fun testAnAliasCompletesInASignatureWithItsImport() {
        val file = myFixture.addFileToProject("src/Http/Completing.phn", "namespace Shop\\Http;\n\nfinal class Completing\n{\n    show(): Ord<caret>\n    {\n    }\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "Order" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("show(): Order"))
        assertTrue(text, text.contains("use Shop\\Orders\\Order;"))
    }

    fun testAnAliasCompletesInAnErasedConstruct() {
        val file = myFixture.addFileToProject("src/Http/Listing.phn", "namespace Shop\\Http;\n\nfinal class Listing\n{\n    all(): list<Lin<caret>>\n    {\n    }\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "Line" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("all(): list<Line>"))
        assertTrue(text, text.contains("use Shop\\Orders\\Line;"))
    }

    fun testAClassCompletesInATypeDeclarationAtTheEndOfAFile() {
        // The declaration is a comment behind the namespace's element: the import still goes into the namespace.
        val file = myFixture.addFileToProject("src/Http/types.phn", "namespace Shop\\Http;\n\ntype Buyers = list<Custo<caret>>;\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "Customer" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("type Buyers = list<Customer>;"))
        assertTrue(text, Regex("""namespace Shop\\Http;\s+use Shop\\Orders\\Customer;""").containsMatchIn(text))
    }
}
