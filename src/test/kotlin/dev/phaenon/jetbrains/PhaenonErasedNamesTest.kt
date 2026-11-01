package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassInspection
import com.jetbrains.php.lang.inspections.PhpUnusedAliasInspection
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * A class name inside an erased type — `list<DomainEvent>` — is whitespace to PhpStorm. It must
 * still navigate to the class, and the import it uses must not be reported as unused.
 */
class PhaenonErasedNamesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testANameInsideAnErasedTypeNavigatesToItsClass() {
        myFixture.configureByFiles("erased/Aggregate.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        assertEquals(listOf("\\App\\Event\\DomainEvent"), targets.map { (it as PhpClass).fqn })
    }

    fun testATypeParameterNavigatesToItsDeclaration() {
        myFixture.configureByFiles("erased/TypeParameter.phn")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        val target = targets.single()
        assertEquals("T", (target as PsiNamedElement).name)
        assertEquals(myFixture.editor.document.text.indexOf("<T of") + 1, target.textOffset)
    }

    fun testTheDeclarationOfATypeParameterIsADeclarationWithUsages() {
        myFixture.configureByFiles("erased/TypeParameterUsages.phn")
        val list = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val text = myFixture.editor.document.text

        val declarations = ExtensionPointName.create<PsiSymbolDeclarationProvider>("com.intellij.psi.declarationProvider")
            .extensionList.flatMap { it.getDeclarations(list, myFixture.caretOffset - list.textRange.startOffset) }
        val parameter = PhaenonErasedTypes.typeParameterDeclaration(list, "T")!!
        val usages = myFixture.findUsages(parameter).map { it.element!!.textRange.startOffset + it.rangeInElement!!.startOffset }.sorted()

        assertEquals(listOf(parameter), declarations.map { PsiSymbolService.getInstance().extractElementFromSymbol(it.symbol) })
        assertEquals(listOf("protected T", "@param T", "@var T", "): T").map { text.indexOf(it) + it.length - 1 }, usages)
    }

    fun testATypeParameterInADocblockNavigatesToItsDeclaration() {
        myFixture.configureByFiles("erased/TypeParameterDoc.phn")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        assertEquals(myFixture.editor.document.text.indexOf("<T of") + 1, targets.single().textOffset)
    }

    fun testATypeParameterInADocblockIsNotAnUndefinedClass() {
        myFixture.enableInspections(PhpUndefinedClassInspection::class.java)
        myFixture.configureByFiles("erased/TypeParameterUsages.phn")

        val undefined = myFixture.doHighlighting().filter { it.description.orEmpty().startsWith("Undefined class") }.map { it.text }

        assertEmpty(undefined)
    }

    fun testFindUsagesSeesANameInsideAnErasedType() {
        myFixture.configureByFiles("erased/Aggregate.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")
        val target = PhpIndex.getInstance(project).getAnyByFQN("\\App\\Event\\DomainEvent").single()

        val usages = myFixture.findUsages(target).map { it.file?.name to it.element?.text?.trim() }

        // The leaf is the whole erased return type, colon included.
        assertContainsElements(usages, "Aggregate.phn" to ": list<DomainEvent>")
    }

    fun testRenamingTheClassRenamesItInsideTheErasedType() {
        myFixture.configureByFiles("erased/Aggregate.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        myFixture.renameElementAtCaret("StoredEvent")

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("list<StoredEvent>"))
        assertTrue(text, text.contains("use App\\Event\\StoredEvent;"))
    }

    fun testAnImportUsedOnlyInAnErasedTypeIsNotUnused() {
        myFixture.enableInspections(PhpUnusedAliasInspection::class.java)
        myFixture.configureByFiles("erased/Aggregate.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        val unused = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && it.description.orEmpty().startsWith("Import") }
            .map { it.text }

        assertEquals(listOf("App\\Event\\Unrelated"), unused)
    }
}

/** Completion and spell-checking inside erased constructs. */
class PhaenonErasedEditingTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testAClassCompletesInsideTypeArgumentsAndIsImported() {
        myFixture.configureByFiles("erased/Completing.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "DomainEvent" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("list<DomainEvent>"))
        assertTrue(text, text.contains("use App\\Event\\DomainEvent;"))
    }

    fun testAQualifiedNameCompletesTheNamespacesMembers() {
        myFixture.configureByFiles("erased/Qualified.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.first { it.lookupString == "DomainEvent" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("list<Event\\DomainEvent>"))
        assertFalse(text, text.contains("use "))
    }

    fun testAnAbsoluteQualifiedNameCompletes() {
        myFixture.configureByFiles("erased/Absolute.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings ?: listOf(myFixture.editor.document.text), "Unrelated")
    }

    fun testKeywordsAndTypeParametersCompleteInsideTypeArguments() {
        myFixture.configureByFiles("erased/Keywords.phn")

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings!!, "string", "static")
    }

    fun testErasedConstructsAreNotSpellChecked() {
        myFixture.enableInspections(com.intellij.spellchecker.inspections.SpellCheckingInspection::class.java)
        myFixture.configureByText("Spelled.phn", "namespace App;\n\n// a xqzvwt word\nfunction f(): list<Xqzvwt>\n{\n    return [];\n}\n")

        val typos = myFixture.doHighlighting().filter { it.description.orEmpty().startsWith("Typo") }.map { it.text }

        assertEquals(listOf("xqzvwt"), typos)
    }
}
