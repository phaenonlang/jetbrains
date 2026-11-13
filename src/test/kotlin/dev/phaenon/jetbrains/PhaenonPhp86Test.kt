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
import com.jetbrains.php.lang.inspections.PhpLanguageLevelInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedConstantInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.attributes.PhpInapplicableAttributeTargetDeclarationInspection
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.PhpCallableFunction
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * PHP 8.6 in `.phn` files (design/php-versions.md §4, §5). The language levels of PhpStorm 2026.1
 * and 2026.2 stop at 8.5, so each construct is an error to them in plain PHP too:
 *
 * - `f(?, 1)`, `f(b: ?)`, `$o->m(?)`, `C::m(?)`: "Expected: )" at the `?`, the rest of the
 *   statement in pieces; `f(1, ...)`: "Expected: expression after spread operator";
 * - `public readonly string $name = 'x'`: "'readonly' property cannot have default value";
 * - `#[\Override]` on a class constant: "Attribute cannot be applied to a class constant because
 *   it does not contain the 'Attribute::TARGET_CLASS CONSTANT' flag" (on an enum case: nothing);
 * - `__debugInfo()` in an enum: "Enum may not include '__debugInfo'";
 * - `CONFIG->level = $v`: "Can't use temporary expression in write context".
 *
 * PhpStorm 2026.3 has an 8.6 level and parses a trailing `...` itself, as its own partial
 * application, which it types. Below 8.6 its language-level inspection, on by default, reports
 * partial application, `#[\Override]` on a constant or an enum case, a readonly default and
 * `__debugInfo()` in an enum ("… is only allowed since PHP 8.6"); the write through a constant
 * stays an error at every level. So the tests run at PHP 8.5, the language's floor and what
 * PhpStorm takes from an 8.5 target's composer.json, and at the IDE's highest level.
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
            PhpLanguageLevelInspection::class.java,
        )
    }

    /** PHP 8.5, and the IDE's highest level: 8.5 up to 2026.2, 8.6 from 2026.3 on. */
    private val levels: List<PhpLanguageLevel> = listOf(PhpLanguageLevel.PHP850, PhpLanguageLevel.values().last()).distinct()

    private fun atEachLevel(check: (PhpLanguageLevel) -> Unit) {
        for (level in levels) {
            PhpProjectConfigurationFacade.getInstance(project).languageLevel = level
            check(level)
        }
    }

    private fun assertThePipeParses() =
        assertTrue("the IDE's highest PHP language level has no pipe", PhpLanguageLevel.values().last() >= PhpLanguageLevel.PHP850)

    fun testPartialApplicationsParseAndRaiseNoFinding() = atEachLevel { level ->
        myFixture.configureByFiles("php86/Partial.phn")

        assertEmpty(level.name, syntaxErrors())
        assertEmpty(level.name, findings())
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
        val rest = assignedValue("rest")
        assertNotNull("a trailing `...` makes a partial application", PhaenonPartialApplication.of(rest))
        if (rest is PhpCallableFunction) {
            // PhpStorm 2026.3 parses the trailing `...` itself, as its own partial application, and
            // types it as the closure alone, in its encoding: the variadic `string ...$tags` is
            // `string[]` there. The plugin adds nothing to it.
            val closures = types["rest"]!!.filter { PhpType.removeParametrisedType(it) == PhpType._CLOSURE && PhpType.getParametrizedParts(it).isNotEmpty() }
            assertEquals(types["rest"].toString(), listOf("\\string", "\\string[]", "\\string"), signature(closures.single()))
            assertFalse("not the callee's result", "\\string" in types["rest"]!!)
        } else {
            assertTrue("`...` takes what is left, the variadic by its element", rest is FunctionReference && closure(listOf("\\string", "\\string"), "\\string") in types["rest"]!!)
        }
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
        atEachLevel { level ->
            myFixture.configureByFiles("php86/Php86.phn")

            assertEquals(PhaenonTarget.PHP86, PhaenonTarget.of(myFixture.file))
            assertEmpty(level.name, syntaxErrors())
            assertEmpty(level.name, findings())
        }
    }

    fun testOnAn85TargetPhpStormsFindingsStayExceptOverrideOnConstants() {
        myFixture.addFileToProject("composer.json", """{"require": {"php": "^8.5"}}""")
        // The level PhpStorm takes from that composer.json.
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.PHP850
        myFixture.configureByFiles("php86/Php86.phn")

        assertEquals(PhaenonTarget.PHP85, PhaenonTarget.of(myFixture.file))
        // The compiler refuses these for 8.5 ("… needs PHP 8.6 (target 8.5)"); it drops
        // `#[\Override]` on a constant, which PHP 8.5 rejects, after checking it. PhpStorm's
        // findings stay in its words: up to 2026.2 "'readonly' property cannot have default value"
        // and "Enum may not include '__debugInfo'", in 2026.3 "… is only available in PHP 8.6 and
        // later" of each; "Can't use temporary expression in write context" in all.
        val found = findings()
        assertSameElements(found.toString(), found.map { it.substringAfter(" @") }, listOf("''derived''", "'__debugInfo'", "'CONFIG->level'"))
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

    private fun assignedValue(name: String) =
        PsiTreeUtil.findChildrenOfType(myFixture.file, AssignmentExpression::class.java).single { (it.variable as? Variable)?.name == name }.value!!

    /** The parameter types and the return type of a closure type in PhpStorm's encoding. */
    private fun signature(closure: String): List<String> = PhpType.getParametrizedParts(closure).let { parts ->
        parts.dropLast(1).map { PhpExpressionImpl.getTypeFromSerializedClosureParameter(it).second?.types?.joinToString("|").orEmpty() } + parts.last()
    }

    private fun assignedTypes(): Map<String, List<String>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java)
            .filter { it.parent is com.jetbrains.php.lang.psi.elements.AssignmentExpression && (it.parent as com.jetbrains.php.lang.psi.elements.AssignmentExpression).variable === it }
            .associate { it.name to it.type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted() }
}
