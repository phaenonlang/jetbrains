package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
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

    fun testAnImportUsedOnlyInAnErasedTypeIsNotUnused() {
        myFixture.enableInspections(PhpUnusedAliasInspection::class.java)
        myFixture.configureByFiles("erased/Aggregate.phn", "erased/DomainEvent.phn", "erased/Unrelated.phn")

        val unused = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && it.description.orEmpty().startsWith("Import") }
            .map { it.text }

        assertEquals(listOf("App\\Event\\Unrelated"), unused)
    }
}
