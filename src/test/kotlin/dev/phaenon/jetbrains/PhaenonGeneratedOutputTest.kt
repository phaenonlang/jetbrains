package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpMultipleClassDeclarationsInspection

/**
 * The `.php` the compiler writes from a `.phn` declares the same classes. When both are in the
 * project, navigation and the duplicate-declaration inspection must treat the source as the
 * declaration; when the project file names an output directory, the IDE must not index it.
 */
class PhaenonGeneratedOutputTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testGoToDeclarationFromPhaenonCodeLandsInTheSource() {
        myFixture.configureByFiles("generated/Consumer.phn", "generated/Label.phn", "generated/Label.php")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        assertTrue(targets.isNotEmpty())
        assertEquals(setOf("Label.phn"), targets.map { it.containingFile.name }.toSet())
    }

    fun testGoToDeclarationFromPlainPhpLandsInTheSource() {
        myFixture.configureByFiles("generated/consumer.php", "generated/Label.phn", "generated/Label.php")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        assertTrue(targets.isNotEmpty())
        assertEquals(setOf("Label.phn"), targets.map { it.containingFile.name }.toSet())
    }

    fun testTheCompiledCopyIsNotAnotherDeclaration() {
        myFixture.enableInspections(PhpMultipleClassDeclarationsInspection::class.java)
        myFixture.configureByFiles("generated/Label.phn", "generated/Label.php")

        val findings = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }

        assertEmpty(findings.filter { it.description.orEmpty().contains("Other declarations") })
    }

    fun testAHandWrittenDuplicateIsStillReported() {
        myFixture.enableInspections(PhpMultipleClassDeclarationsInspection::class.java)
        myFixture.configureByFiles("generated/Label.phn", "generated/Label.php", "generated/LabelByHand.php")

        val findings = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }

        assertNotEmpty(findings.filter { it.description.orEmpty().contains("Other declarations") })
    }

    fun testTheOutputDirectoriesOfTheProjectFileAreExcluded() {
        val projectFile = myFixture.copyFileToProject("generated/phaenon.json", "phaenon.json")

        val policy = DirectoryIndexExcludePolicy.EP_NAME.getExtensions(project).filterIsInstance<PhaenonExcludePolicy>().single()
        val urls = policy.excludeUrlsForProject.toList()

        val root = projectFile.parent.url
        assertEquals(setOf("$root/.src", "$root/.tests"), urls.toSet())
    }
}
