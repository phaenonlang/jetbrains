package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import com.jetbrains.php.lang.inspections.PhpUndefinedConstantInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.attributes.PhpInapplicableAttributeTargetDeclarationInspection
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * PHP 8.6 in `.phn` files (design/php-versions.md §4, §5). PhpStorm's language levels stop at 8.5
 * (2026.1 through 2026.3), so each construct is an error to it in plain PHP too:
 *
 * - `f(?, 1)`, `f(b: ?)`, `$o->m(?)`, `C::m(?)`: "Expected: )" at the `?`, the rest of the
 *   statement in pieces; `f(1, ...)`: "Expected: expression after spread operator";
 * - `public readonly string $name = 'x'`: "'readonly' property cannot have default value";
 * - `#[\Override]` on a class constant: "Attribute cannot be applied to a class constant because
 *   it does not contain the 'Attribute::TARGET_CLASS CONSTANT' flag" (on an enum case: nothing);
 * - `__debugInfo()` in an enum: "Enum may not include '__debugInfo'";
 * - `CONFIG->level = $v`: "Can't use temporary expression in write context".
 *
 * The test project lacks PhpStorm's bundled stubs, so the one declaring `Attribute` and
 * `Override` is copied in from the PHP plugin itself: the attribute targets are each IDE's own.
 */
class PhaenonPhp86Test : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        val core = PhpLanguage::class.java.classLoader.getResource("stubs/Core/Core_c.php")?.readText()
        if (core != null) myFixture.addFileToProject("stubs/Core_c.php", core)
        myFixture.enableInspections(
            PhpUndefinedConstantInspection::class.java,
            PhpUndefinedMethodInspection::class.java,
            PhpParamsInspection::class.java,
            PhpInapplicableAttributeTargetDeclarationInspection::class.java,
        )
    }

    private fun assertThePipeParses() =
        assertTrue("the IDE's highest PHP language level has no pipe", PhpLanguageLevel.values().last() >= PhpLanguageLevel.PHP850)

    fun testPartialApplicationsParseAndRaiseNoFinding() {
        myFixture.configureByFiles("php86/Partial.phn")

        assertEmpty(syntaxErrors())
        assertEmpty(findings())
    }

    fun testReformatCodeLeavesPlaceholdersAsWritten() {
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        val file = myFixture.configureByFiles("php86/Partial.phn").single()
        val before = file.text
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        assertEquals(before, myFixture.editor.document.text)
    }

    fun testAPartialApplicationIsTypedAsTheClosureItCreates() {
        myFixture.configureByFiles("php86/Partial.phn")

        val types = assignedTypes()
        assertTrue(types["positional"].toString(), closure(listOf("\\int"), "\\string") in types["positional"]!!)
        assertTrue("`...` takes what is left, the variadic by its element", closure(listOf("\\string", "\\string"), "\\string") in types["rest"]!!)
        assertTrue("a named placeholder", closure(listOf("\\string"), "\\string") in types["named"]!!)
        assertTrue("a method", closure(listOf("\\float"), "\\float") in types["method"]!!)
        assertTrue("a static method", closure(listOf("\\float"), "\\float") in types["static"]!!)
        assertTrue("a call of the closure", "\\string" in types["called"]!!)
    }

    fun testAPipeStageIsTypedAsTheCallItBecomes() {
        assertThePipeParses()
        myFixture.configureByFiles("php86/PartialPipe.phn")

        assertEmpty(syntaxErrors())
        assertEmpty(findings())
        val types = assignedTypes()
        assertEquals("a function stage, then a first-class callable", listOf("\\string"), types["line"])
        assertEquals("a static method stage", listOf("\\int"), types["width"])
        assertEquals("one stage feeding the next", listOf("\\int"), types["chained"])
    }

    fun testPhp86ConstructsAreFreeOfFindingsOnAn86Target() {
        myFixture.addFileToProject("phaenon.json", """{"roots": {"php86": ".php86"}, "php": "8.6"}""")
        myFixture.configureByFiles("php86/Php86.phn")

        assertEquals(PhaenonTarget.PHP86, PhaenonTarget.of(myFixture.file))
        assertEmpty(syntaxErrors())
        assertEmpty(findings())
    }

    fun testOnAn85TargetPhpStormsFindingsStayExceptOverrideOnConstants() {
        myFixture.addFileToProject("composer.json", """{"require": {"php": "^8.5"}}""")
        myFixture.configureByFiles("php86/Php86.phn")

        assertEquals(PhaenonTarget.PHP85, PhaenonTarget.of(myFixture.file))
        // The compiler refuses these for 8.5 ("… needs PHP 8.6 (target 8.5)"); it drops
        // `#[\Override]` on a constant, which PHP 8.5 rejects, after checking it.
        assertSameElements(
            findings().map { it.substringBefore(" @") },
            "'readonly' property cannot have default value",
            "Enum may not include '__debugInfo'",
            "Can't use temporary expression in write context",
        )
    }

    fun testTheTargetFollowsTheProjectFiles() {
        myFixture.addFileToProject("composer.json", """{"require": {"php": ">=8.6"}}""")
        myFixture.configureByFiles("php86/Php86.phn")
        assertEquals("composer.json's floor", PhaenonTarget.PHP86, PhaenonTarget.of(myFixture.file))

        myFixture.addFileToProject("php86/phaenon.json", """{"php": "8.5"}""")
        myFixture.configureByFiles("php86/Partial.phn")
        assertEquals("phaenon.json wins, the nearest", PhaenonTarget.PHP85, PhaenonTarget.of(myFixture.file))
    }

    fun testComposerConstraintsReadAsComposerReadsThem() {
        // The cases of the compiler's phpversion.TestLowestAdmitted.
        val cases = mapOf(
            ">=8.5" to "8.5", ">= 8.5" to "8.5", "^8.5" to "8.5", "^8.5.1" to "8.5", "~8.5.0" to "8.5", "~8.5" to "8.5",
            "~8" to "8.0", "8.5.*" to "8.5", "8.*" to "8.0", "8.5.x" to "8.5", "v8.6" to "8.6", "8.6.0" to "8.6",
            "==8.6.2" to "8.6", "^8.5 || ^9.0" to "8.5", "^9.0 || ^8.6" to "8.6", "^8.6|^8.5" to "8.5",
            ">=8.5 <8.7" to "8.5", ">=8.5,<8.7" to "8.5", ">=8.5, <8.7" to "8.5", ">8.5" to "8.5", ">8.5 <9" to "8.5",
            "8.5 - 8.6" to "8.5", "8.5.2 - 8.6.0" to "8.5", ">=8.6@dev" to "8.6", "^8.6@stable" to "8.6",
            ">=8.6.0-dev" to "8.6", "^8.6.0-beta1" to "8.6", "^8.6.0RC1" to "8.6", ">=8.6 <8.5 || ^8.5" to "8.5",
            "<9 >=8.4" to "8.4", "^0.3" to "0.3",
        )
        for ((constraint, expected) in cases) {
            assertEquals(constraint, expected, PhaenonTarget.lowestAdmitted(constraint)?.toString())
        }
        for (unbounded in listOf("*", "<9", "!=8.4", "dev-master")) {
            assertNull(unbounded, PhaenonTarget.lowestAdmitted(unbounded))
        }
    }

    private fun syntaxErrors(): List<String> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java).map { "${it.errorDescription} @${it.textOffset}" }

    private fun findings(): List<String> = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} @'${it.text}'" }

    private fun assignedTypes(): Map<String, List<String>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java)
            .filter { it.parent is com.jetbrains.php.lang.psi.elements.AssignmentExpression && (it.parent as com.jetbrains.php.lang.psi.elements.AssignmentExpression).variable === it }
            .associate { it.name to it.type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted() }
}
