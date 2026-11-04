package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import java.io.File

/**
 * A `.phn` indents with spaces (D21), and Reformat Code ends with phn fmt's first layer
 * ([PhaenonPostFormatProcessor]). The layer's tests need a `phn`: `PHAENON_PHN=/path/to/phn`, a
 * build of the compiler (`go build -o bin/phn ./cmd/phn`); they are skipped without one. They share
 * their golden files with the compiler, `../phaenon/internal/format/testdata`.
 */
class PhaenonLayoutTest : BasePlatformTestCase() {

    private val phn: String? = System.getenv("PHAENON_PHN")?.takeIf { it.isNotBlank() && File(it).canExecute() }

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        PhaenonSettings.of(project).binary = phn ?: "/nonexistent/phn"
    }

    override fun tearDown() {
        try {
            PhaenonSettings.of(project).binary = ""
            CodeStyle.getSettings(project).getLanguageIndentOptions(PhpLanguage.INSTANCE).USE_TAB_CHARACTER = false
        } finally {
            super.tearDown()
        }
    }

    fun testAPhnFileIndentsWithSpacesWhenPhpIndentsWithTabs() {
        CodeStyle.getSettings(project).getLanguageIndentOptions(PhpLanguage.INSTANCE).USE_TAB_CHARACTER = true
        val phn = myFixture.configureByText("A.phn", "namespace App;\n\nfinal class A\n{\nfun f(): int {\nreturn 1;\n}\n}\n")
        assertFalse(CodeStyle.getIndentOptions(phn).USE_TAB_CHARACTER)
        reformat()
        assertEquals("namespace App;\n\nfinal class A\n{\n    fun f(): int {\n        return 1;\n    }\n}\n", myFixture.editor.document.text)

        val php = myFixture.configureByText("A.php", "<?php\nfinal class A\n{\npublic function f(): int\n{\nreturn 1;\n}\n}\n")
        assertTrue("a .php keeps the PHP style", CodeStyle.getIndentOptions(php).USE_TAB_CHARACTER)
    }

    fun testWithoutAPhnReformatCodeIsPhpStormsAlone() {
        PhaenonSettings.of(project).binary = "/nonexistent/phn"
        myFixture.configureByText("A.phn", "namespace App;\n\nfun f(): list < int > {\n    return [];\n}\n")
        reformat()
        assertTrue(myFixture.editor.document.text.contains("list < int >"))
    }

    fun testReformatCodeWritesPhaenonsLayout() {
        if (phn == null) return
        myFixture.configureByText("A.phn", "\n\nnamespace App;\n\nfinal class Box<T of \\Countable>\n{\n    /**\n     * The first.\n     */\n    fun first(list < T > \$xs): ?T throws  \\LogicException {\n        return \$xs[0] ?? null;\n    }\n}\n")
        reformat()
        assertEquals(
            "namespace App;\n\nfinal class Box<T of \\Countable>\n{\n    /** The first. */\n    fun first(list<T> \$xs): ?T throws \\LogicException {\n        return \$xs[0] ?? null;\n    }\n}\n",
            myFixture.editor.document.text,
        )
    }

    fun testAReformatOfARangeLeavesTheRestAlone() {
        if (phn == null) return
        val text = "namespace App;\n\nfun f(): list < int > {\n    return [];\n}\n\nfun g(): int {\nreturn 1;\n}\n"
        val file = myFixture.configureByText("A.phn", text)
        val g = text.indexOf("fun g")
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformatText(file, g, text.length) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val out = myFixture.editor.document.text
        assertTrue("the range was formatted:\n$out", out.contains("    return 1;"))
        assertTrue("the rest was left alone:\n$out", out.contains("list < int >"))
    }

    /**
     * Reformat Code on the compiler's format goldens: PhpStorm's PHP style plus phn fmt's first
     * layer, where `phn fmt` leaves PHP's own spacing to the project's fixer. So the input and the
     * golden reformat to one text, in Phaenon's layout (the layer changes it no more), that differs
     * from the golden in PHP's whitespace only.
     */
    fun testTheCompilersGoldens() {
        if (phn == null) return
        val dir = File("../phaenon/internal/format/testdata")
        if (!dir.isDirectory) return
        // tabs.phn: Reformat Code of a heredoc indented with tabs trips PhpStorm's highlighter in a
        // .phn (PhpHeredocPostFormatProcessor's edits; open item). Such a file does not compile
        // since D21, and the server's quick-fix or phn fmt replaces the tabs.
        val inputs = dir.listFiles { f -> f.name.endsWith(".phn") && !f.name.endsWith(".golden.phn") && f.name != "tabs.phn" }!!.sortedBy { it.name }
        assertTrue(inputs.isNotEmpty())
        val failures = mutableListOf<String>()
        fun reformatted(name: String, text: String): String {
            myFixture.configureByText(name, text)
            reformat()
            return myFixture.editor.document.text
        }
        fun tokens(text: String) = text.replace(Regex("\\s+"), "")
        for (input in inputs) {
            val golden = File(dir, input.name.removeSuffix(".phn") + ".golden.phn").readText()
            val fromInput = reformatted(input.name, input.readText())
            val fromGolden = reformatted("golden-" + input.name, golden)
            when {
                fromInput != fromGolden -> failures += "${input.name}: the input and the golden reformat differently:\n$fromInput\n---\n$fromGolden"
                PhaenonCompiler.layout(project, null, fromInput) != fromInput -> failures += "${input.name}: not in Phaenon's layout:\n$fromInput"
                tokens(fromInput) != tokens(golden) -> failures += "${input.name}: more than whitespace differs from the golden:\n$fromInput"
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    /** Reformat Code's path, the whole file's text range, and the layout that follows it. */
    private fun reformat() {
        val file = myFixture.file
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, 0, file.textLength)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
