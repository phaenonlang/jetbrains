package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle

/**
 * Nested calls to a pipe expression ([PhaenonPipeConversionIntention], [PhaenonPipeQuickFixFilter]).
 *
 * PhpStorm's inspection and quick-fix came after its PHP 8.5 support: 2025.3 has neither, every
 * PhpStorm from 2026.1 on has both, so all of these run on the PhpStorm the plugin is built against.
 */
class PhaenonPipeConversionTest : BasePlatformTestCase() {

    private val body = """
        namespace App;

        final class Style
        {
            public static function line(string ${'$'}s): string
            {
                return ${'$'}s;
            }
        }

        final class Out
        {
            public function writeln(string ${'$'}s): void
            {
            }
        }

        final class Receipt
        {
            public function __construct(private Out ${'$'}output)
            {
            }

            public function run(string ${'$'}order): void
            {
                ${'$'}this->output->writeln(Style::line(sprin<caret>tf('✓ order %s', ${'$'}order)));
            }
        }
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
    }

    private fun pipesExist(): Boolean = PhpLanguageLevel.values().last().versionString.let { it >= "8.5" }

    private fun phpStormsInspection(): LocalInspectionTool? =
        runCatching { Class.forName("com.jetbrains.php.lang.inspections.PhpPipeOperatorCanBeUsedInspection") }.getOrNull()
            ?.getDeclaredConstructor()?.newInstance() as? LocalInspectionTool

    private fun conversions(): List<String> = myFixture.availableIntentions.map { it.text }.filter { it == "Convert nested function calls to a pipe expression" }

    fun testThereIsNoConversionWhereTheLanguageLevelHasNoPipe() {
        myFixture.configureByText("Low.phn", body)
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.PHP840

        assertEmpty(conversions())
    }

    fun testPhpStormsFixTurnsMethodCallsIntoTheirQualifiersInAPhpFile() {
        val inspection = phpStormsInspection() ?: return
        myFixture.enableInspections(inspection)
        myFixture.configureByText("Receipt.php", "<?php\n$body")

        myFixture.launchAction(myFixture.availableIntentions.single { it.text == "Convert nested function calls to a pipe expression" })

        // PhpStorm's own result, recorded: when it writes `Style::line(...)` here, the plugin's
        // replacement in .phn files can go.
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("|> Style(...)") && text.contains("|> \$this->output(...)"))
    }

    fun testAPhnFileGetsTheConversionThatKeepsTheMethods() {
        if (!pipesExist()) return
        phpStormsInspection()?.let { myFixture.enableInspections(it) }
        myFixture.configureByText("Receipt.phn", body.replace("public function ", ""))

        assertEquals("PhpStorm's fix is replaced, not doubled", 1, conversions().size)
        myFixture.launchAction(myFixture.availableIntentions.single { it.text == "Convert nested function calls to a pipe expression" })

        val text = myFixture.editor.document.text
        val pipe = text.substringAfter("    run(string \$order): void\n    {\n").substringBefore("\n    }")
        assertEquals("sprintf('✓ order %s', \$order) |> Style::line(...) |> \$this->output->writeln(...);", pipe.trim().replace(Regex("\\s*\\n\\s*"), " "))
    }

    fun testFunctionsBecomeCallablesAndACallWithMoreArgumentsAnArrowFunction() {
        if (!pipesExist()) return
        myFixture.configureByText(
            "Calls.phn",
            "namespace App;\n\nfunction f(string \$x): string\n{\n    return str_pad(tr<caret>im(strval(\$x)), 10);\n}\n",
        )

        myFixture.launchAction(myFixture.availableIntentions.single { it.text == "Convert nested function calls to a pipe expression" })

        val text = myFixture.editor.document.text.substringAfter("return ").substringBefore(";").replace(Regex("\\s*\\n\\s*"), " ")
        assertEquals("\$x |> strval(...) |> trim(...) |> (fn(\$x1) => str_pad(\$x1, 10))", text)
    }
}
