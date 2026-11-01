package dev.phaenon.jetbrains

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import java.io.File

/**
 * A diagnostic, not a check: with `PHAENON_DEBUG_FILES=a.phn:b.phn` it parses each file as the
 * plugin does and prints its parse errors with line numbers, which the real-tree test only counts.
 */
class PhaenonFileDebugTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testPrintParseErrorsOfTheGivenFiles() {
        val files = System.getenv("PHAENON_DEBUG_FILES")?.split(':')?.filter { it.isNotBlank() } ?: return
        for (path in files) {
            val text = File(path).readText()
            val file = myFixture.configureByText(File(path).name, text)
            val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
            println("== $path: ${errors.size} errors")
            for (e in errors.take(5)) {
                val line = text.substring(0, e.textOffset).count { it == '\n' } + 1
                println("  line $line: ${e.errorDescription} at '${text.substring(e.textOffset, minOf(text.length, e.textOffset + 30)).replace('\n', ' ')}'")
            }
        }
    }
}
