package dev.phaenon.jetbrains

import com.intellij.codeInsight.CodeInsightWorkspaceSettings
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.codeInsight.PhpImportOptimizer
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.psi.elements.PhpUse

/**
 * Optimize Imports in a `.phn` file keeps an import that only an erased construct uses — a type
 * argument, a union member, a return type, a closure signature, an `is` / `as` type — as it keeps
 * one a docblock type uses, still removes one nothing uses, and writes one import per line. The
 * tests run the platform's optimizer, the one every entry point goes through, on the fixtures in
 * `testData/imports/` (`X.phn` → `X.after.phn`); the classes the imports name are in `Model.phn`,
 * since PhpStorm never removes an import of a class it cannot find.
 */
class PhaenonImportOptimizerTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyFileToProject("imports/Model.phn")
    }

    fun testAnImportOnlyAnErasedConstructUsesIsKeptAndAnUnusedOneRemoved() {
        // The block goes in unsorted; it comes back sorted, the imports put back included.
        optimize("Erased.phn")
    }

    fun testImportsAreKeptInABlockTheOptimizerEmptied() = optimize("Only.phn")

    fun testEachImportGetsALineOfItsOwn() = optimize("Layout.phn")

    fun testABracedNamespaceKeepsItsIndentation() = optimize("Braced.phn")

    fun testFunctionImportsKeepTheBlankLineBeforeThem() = optimize("Kinds.phn")

    fun testABlockOfUnusedImportsGoesWithoutLeavingSpace() = optimize("Unused.phn")

    fun testAnAliasAndAGroupMemberAreKept() {
        val file = myFixture.configureByFile("imports/Aliased.phn")

        OptimizeImportsProcessor(project, file).runWithoutProgress()

        assertEquals(listOf("App\\Model\\Converted as Target", "App\\Model\\GroupedErased", "App\\Model\\GroupedNative"), imports(file))
    }

    fun testTheNotificationCountsOnlyWhatWasRemoved() {
        val file = myFixture.configureByFile("imports/Erased.phn")

        val optimize = PhaenonImportOptimizer().processFile(file)
        WriteCommandAction.runWriteCommandAction(project) { optimize.run() }

        assertEquals("1 'use' statement removed", (optimize as ImportOptimizer.CollectingInfoRunnable).userNotificationInfo)
    }

    fun testEveryEntryPointGetsThePhaenonOptimizerForPhnAndPhpStormsForPhp() {
        // Optimize Imports, Reformat Code with imports, the save action and the pre-commit step all
        // take their optimizers from this registry.
        val phn = LanguageImportStatements.INSTANCE.forFile(myFixture.configureByFile("imports/Erased.phn")).map { it.javaClass }
        val php = LanguageImportStatements.INSTANCE.forFile(myFixture.configureByFile("imports/Plain.php")).map { it.javaClass }

        assertEquals(listOf(PhaenonImportOptimizer::class.java), phn)
        assertContainsElements(php, PhpImportOptimizer::class.java)
        assertDoesntContain(php, PhaenonImportOptimizer::class.java)
    }

    fun testAPhpFileIsOptimizedAsBefore() {
        val file = myFixture.configureByFile("imports/Plain.php")

        OptimizeImportsProcessor(project, file).runWithoutProgress()

        myFixture.checkResultByFile("imports/Plain.after.php")
    }

    fun testOptimizingOnTheFlyKeepsThemToo() {
        // The PHP plugin has no import optimization on the fly; were it to get one, it would take
        // the optimizer from the same registry. Either way the imports must survive highlighting.
        CodeInsightWorkspaceSettings.getInstance(project).setOptimizeImportsOnTheFly(true, testRootDisposable)
        myFixture.configureByFile("imports/Erased.phn")

        myFixture.doHighlighting()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertContainsElements(imports(myFixture.file), ERASED_ONLY.map { "App\\Model\\$it" })
    }

    /** Runs Optimize Imports on `imports/[name]` and compares the result with its `.after` file. */
    private fun optimize(name: String) {
        val file = myFixture.configureByFile("imports/$name")

        OptimizeImportsProcessor(project, file).runWithoutProgress()

        myFixture.checkResultByFile("imports/" + name.replace(".phn", ".after.phn"))
    }

    /** The imports of [file], written `Fqn` or `Fqn as Alias`, sorted. */
    private fun imports(file: PsiFile): List<String> =
        PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java)
            .map { it.fqn.trimStart('\\') + (it.aliasName?.let { alias -> " as $alias" } ?: "") }
            .sorted()

    private companion object {
        val ERASED_ONLY = listOf("ArgOnly", "AsOnly", "IsOnly", "QualifiedSigOnly", "ReturnOnly", "SigOnly", "UnionOnly")
    }
}
