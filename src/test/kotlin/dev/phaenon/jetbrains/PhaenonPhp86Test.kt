package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.config.PhpLanguageFeature
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
 * PhpStorm 2026.3 has an 8.6 level and parses placeholders itself: a `.phn`'s `?` reaches it as
 * PHP's own, and a partial application is PhpStorm's, which it types. Below 8.6 its language-level
 * inspection, on by default, reports partial application, `#[\Override]` on a constant or an enum
 * case, a readonly default and `__debugInfo()` in an enum ("… is only allowed since PHP 8.6", "…
 * is only available in PHP 8.6 and later"); the write through a constant stays an error at every
 * level. So the tests run at PHP 8.5, the language's floor and what PhpStorm takes from an 8.5
 * target's composer.json, and at the IDE's highest level, and expect each build's own types and
 * words where they differ, chosen by what the build has.
 *
 * The test project lacks PhpStorm's bundled stubs, so the one declaring `Attribute` and
 * `Override` is copied in from the PHP plugin itself: the attribute targets are each IDE's own.
 */
class PhaenonPhp86Test : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    /** The light project's level before this test: it outlives the test, so it is put back. */
    private var levelBefore: PhpLanguageLevel? = null

    override fun setUp() {
        super.setUp()
        levelBefore = PhpProjectConfigurationFacade.getInstance(project).languageLevel
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

    override fun tearDown() {
        try {
            levelBefore?.let { PhpProjectConfigurationFacade.getInstance(project).languageLevel = it }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
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
        assertPartial(types, "positional", listOf("\\int"), "\\string")
        // `...` takes what is left, the variadic by its element.
        assertPartial(types, "rest", listOf("\\string", "\\string"), "\\string", variadic = true)
        assertPartial(types, "mixed", listOf("\\int", "\\string", "\\string"), "\\string", variadic = true)
        assertPartial(types, "named", listOf("\\string"), "\\string")
        assertPartial(types, "method", listOf("\\float"), "\\float")
        assertPartial(types, "static", listOf("\\float"), "\\float")
        assertTrue("a call of the closure", "\\string" in types["called"]!!)
        assertTrue("a call of `...`'s closure", "\\string" in types["restCalled"]!!)
        assertTrue("a call of the mixed form's closure", "\\string" in types["mixedCalled"]!!)
    }

    fun testATrailingPlaceholderStandsForTheRequiredParametersLeft() {
        // Up to 2026.2 the call is a call with a spread to PhpStorm, which counts its arguments:
        // "Required parameter '$value' missing" for each `...` here, on either target. The
        // compiler lowers all of them for 8.5. A closure called at once gets what it needs: a
        // pipe stage one argument for its one parameter, `frame('<', ...)('x', '>')` two.
        for (target in listOf(PhaenonTarget.PHP85, PhaenonTarget.PHP86)) {
            if (target == PhaenonTarget.PHP86) myFixture.addFileToProject("phaenon.json", """{"php": "8.6"}""")
            atEachLevel { level ->
                myFixture.configureByFiles("php86/PartialRest.phn")

                assertEquals(target, PhaenonTarget.of(myFixture.file))
                assertEmpty("$target ${level.name}", syntaxErrors())
                assertEmpty("$target ${level.name}", findings())
            }
        }
        val types = assignedTypes()
        assertPartial(types, "function", listOf("\\string"), "\\string")
        assertPartial(types, "method", listOf("\\float"), "\\float")
        assertPartial(types, "static", listOf("\\int"), "\\float")
        assertPartial(types, "named", listOf("\\string"), "\\string")
        assertPartial(types, "mixed", listOf("\\string", "\\string"), "\\string")
        assertPartial(types, "frame", listOf("\\string", "\\string"), "\\string")
        assertEquals("a pipe stage, the call it becomes", listOf("\\int"), types["width"])
        assertEquals("a function stage, the call it becomes", listOf("\\string"), types["line"])
    }

    fun testACallThatLacksAnArgumentKeepsItsFinding() = atEachLevel { level ->
        myFixture.configureByFiles("php86/PartialMissing.phn")

        // A call without placeholders, around a partial application, inside one or alone, lacks
        // its argument in every build. Up to 2026.2 PhpStorm says so as well of a partial
        // application that PHP rejects, if in the words of the call: without a trailing `...`
        // (`label(?)`, "expects exactly 2 arguments"), with a `...` that is not last, through
        // `new` or `?->`; and of one whose closure is called at once with fewer arguments than it
        // needs (`ArgumentCountError`): a pipe stage given one of two, `label('◇ ', ...)()` none of
        // one. None goes. 2026.3 parses them as partial applications: it reports the pipe stages
        // and `?->` otherwise and a closure called at once on that call.
        val missing = mutableListOf(
            missing("\$value", "'('✓ ')'"),
            missing("\$body, \$close", "'('[')'"),
            missing("\$c", "'()'"),
            missing("\$value", "'(take(label('✓ ', ...)))'"),
            missing("\$value", "'('◦ ')'"),
        )
        if (PhaenonPartialApplication.phpStormParsesPlaceholders) {
            missing += missing("\$value", "'()'")
            missing += missing("\$close", "'('x')'")
            missing += missing("\$body", "'(close: '»')'")
        } else {
            missing += missing("\$value", "'(?)'")
            missing += missing("\$prefix", "'(..., value: 'x')'")
            missing += missing("\$b", "'(1, ...)'")
            missing += missing("\$body, \$close", "'('(', ...)'")
            missing += missing("\$low, \$high", "'(1.0, ...)'")
            missing += missing("\$value", "'(prefix: ?, ...)'")
            missing += missing("\$value", "'(?, ...)'")
            missing += missing("\$value", "'(3, ...)'")
            missing += missing("\$value", "'('◇ ', ...)'")
            missing += missing("\$body, \$close", "'('{', ...)'")
            missing += missing("\$body, \$close", "'('«', ...)'")
        }
        val found = findings().filter(::isMissingArgument)
        assertEquals(level.name, missing.size, missing.toSet().size)
        assertSameElements(level.name, found, missing)
    }

    fun testATrailingPlaceholderInAPhpFileKeepsPhpStormsFindings() = atEachLevel { level ->
        myFixture.configureByText("Rest.php", "<?php\nnamespace App;\n\nfunction label(string \$prefix, string \$value): string {\n    return \$prefix . \$value;\n}\n\n\$f = label('✓ ', ...);\n")

        val found = findings().filter(::isMissingArgument)
        // The `.phn` rule reaches no `.php`; 2026.3 parses the `...` itself and reports nothing.
        if (PhaenonPartialApplication.phpStormParsesPlaceholders) assertEmpty(level.name, found)
        else assertSameElements(level.name, found, listOf(missing("\$value", "'('✓ ', ...)'")))
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
        // findings stay in its words: its language-level inspection's where the build has the
        // feature (2026.3, "… is only available in PHP 8.6 and later"), else its annotator's.
        val readonlyDefault = featureMessage("READONLY_PROPERTY_DEFAULTS") ?: PhpBundle.message("readonly.property.cannot.have.default.value")
        val debugInfo = featureMessage("DEBUG_INFO_IN_ENUMS") ?: PhpBundle.message("enum.may.not.include.0", "__debugInfo")
        val temporary = PhpBundle.message("cant.use.temporary.expression.in.write.context")
        assertSameElements(
            findings(),
            "$readonlyDefault @''derived''",
            "$debugInfo @'__debugInfo'",
            "$debugInfo @'Describes'",
            "$temporary @'CONFIG->level'",
        )
    }

    fun testPhpFilesKeepPhpStormsFindings() {
        myFixture.addFileToProject("phaenon.json", """{"php": "8.6"}""")
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.PHP850
        val text = java.io.File(testDataPath, "php86/Php86.phn").readText()
        myFixture.configureByText("Php86.php", "<?php\n" + text.replace("public __debugInfo", "public function __debugInfo"))

        // The `.phn` rules reach no `.php`, whatever the target: each construct keeps its finding.
        assertSameElements(
            findings().map { it.substringAfter(" @") },
            "'\\Override'", "'\\Override'", "''derived''", "'__debugInfo'", "'Describes'", "'CONFIG->level'",
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

    /** PhpStorm's "Required parameter '{0}' missing" and its plural, around the names. */
    private val MISSING: List<Pair<String, String>> by lazy {
        listOf("inspection.missing_param", "required.parameters.missing")
            .map { PhpBundle.message(it, "\u0000") }
            .map { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }
    }

    private fun isMissingArgument(finding: String): Boolean =
        MISSING.any { (prefix, suffix) -> finding.startsWith(prefix) && finding.substringBefore(" @").endsWith(suffix) }

    /** The finding PhpStorm writes for [names] missing at [anchor], in its words: singular or plural. */
    private fun missing(names: String, anchor: String): String =
        PhpBundle.message(if (", " in names) "required.parameters.missing" else "inspection.missing_param", names) + " @" + anchor

    /** [PhpLanguageFeature]'s message of the feature named, null where this PhpStorm has none. */
    private fun featureMessage(name: String): String? =
        PhpLanguageFeature.values().firstOrNull { it.name == name }?.notSupportedErrorMessage

    /**
     * [name] holds the closure of a partial application from [parameters] to [returns], the last
     * parameter [variadic]: PhpStorm's own partial application (2026.3), the closure alone in its
     * encoding, parameter names included and a variadic as an array; or, up to 2026.2, a call the
     * plugin types as its closure, beside the callee's result.
     */
    private fun assertPartial(types: Map<String, List<String>>, name: String, parameters: List<String>, returns: String, variadic: Boolean = false) {
        val value = assignedValue(name)
        val type = types[name]!!
        assertNotNull("$name is a partial application", PhaenonPartialApplication.of(value))
        assertEquals("$name is PhpStorm's own where its parser takes placeholders", PhaenonPartialApplication.phpStormParsesPlaceholders, value is PhpCallableFunction)
        if (value is PhpCallableFunction) {
            val closures = type.filter { PhpType.removeParametrisedType(it) == PhpType._CLOSURE && PhpType.getParametrizedParts(it).isNotEmpty() }
            val expected = (if (variadic) parameters.dropLast(1) + (parameters.last() + "[]") else parameters) + returns
            assertEquals("$name: $type", expected, signature(closures.single()))
            assertFalse("$name: the closure alone, not the callee's result: $type", returns in type)
        } else {
            assertTrue("$name: $type", value is FunctionReference && closure(parameters, returns) in type)
        }
    }

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
