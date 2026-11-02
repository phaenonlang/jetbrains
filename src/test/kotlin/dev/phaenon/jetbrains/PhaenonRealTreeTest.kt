package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import java.io.File

/**
 * Runs the plugin over real Phaenon source trees: every `.phn` file is added to the test project
 * and parsed, stubbed and typed as it would be in the IDE.
 *
 * The fixtures under `testData` cover constructs; this covers volume. The trees are the Tequila
 * packages migrated to Phaenon, which `phn check` parses without a finding, so every syntax error
 * the PHP parser reports on them is an erasure the plugin got wrong — with one exception: PHP
 * syntax newer than the highest language level this PhpStorm knows is an error in `.php` files as
 * well (the pipe operator `|>` of PHP 8.5 in PhpStorm 2025.1, whose levels stop at 8.4). Files
 * using it are skipped and listed when the IDE lacks the level, so that the run stays meaningful
 * on an older IDE and checks them on a newer one.
 *
 * The test runs only when `PHAENON_TREES` names the trees to scan — colon-separated directories,
 * each walked recursively, `vendor` excluded — and passes trivially otherwise, so that the plain
 * test suite stays self-contained:
 *
 * ```sh
 * PHAENON_TREES=~/labs/tequila/core:~/labs/tequila/mongodb-odm ./gradlew test --tests '*RealTree*'
 * ```
 *
 * The listing of findings is capped per tree; the totals are not.
 */
class PhaenonRealTreeTest : BasePlatformTestCase() {

    private class Loaded(val relative: String, val text: String, val file: PsiFile)

    private class Tree(val root: File, val files: List<Loaded>, val skipped: List<String>)

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        // The test framework turns every recursion PhpStorm's type inference prevents into an
        // error (`CachingPreventedException`, "TypeProvider contract violation?"), where the IDE
        // just settles for a partial type. Real code has such cycles — a script re-typing `$socket`
        // in a `while (true)`, methods calling each other whose Phaenon return types are erased
        // and so inferred from their bodies — and PhpStorm's own providers hit them (`PhpArrayAccessTP`,
        // `PhpParameterBasedTypeProvider`). This test measures what the IDE does with the trees,
        // so the assertion is off here; the fixture tests keep it on for the plugin's provider.
        RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    fun testRealTreesParseWithoutSyntaxErrorsAndStubConsistently() {
        val trees = loadTrees() ?: return
        val report = StringBuilder()
        var failed = false
        for (tree in trees) {
            var filesWithErrors = 0
            var errorCount = 0
            var stubMismatches = 0
            val listing = ArrayList<String>()
            val phpStormFailsToo = ArrayList<String>()
            for (loaded in tree.files) {
                assertSame("${loaded.relative} must parse through the Phaenon root", PhaenonFileElementType, loaded.file.node.elementType)
                val errors = PsiTreeUtil.collectElementsOfType(loaded.file, PsiErrorElement::class.java)
                if (errors.isNotEmpty() && failsAsPlainPhp(loaded.text, errors)) {
                    // PhpStorm's own parser rejects the same PHP at the same places (`[...$a][0]`
                    // on 2025.1): not the plugin's erasure, listed and not counted.
                    phpStormFailsToo.add("${loaded.relative}:${line(loaded.text, errors.first().textOffset)}")
                } else if (errors.isNotEmpty()) {
                    filesWithErrors++
                    errorCount += errors.size
                    for (error in errors) {
                        if (listing.size >= LISTING_CAP) break
                        listing.add("${loaded.relative}:${line(loaded.text, error.textOffset)}: ${error.errorDescription} (near '${near(loaded.text, error.textOffset)}')")
                    }
                }
                try {
                    PsiTestUtil.checkStubsMatchText(loaded.file)
                } catch (e: AssertionError) {
                    stubMismatches++
                    if (listing.size < LISTING_CAP) listing.add("${loaded.relative}: stubs differ from text: ${e.message?.lineSequence()?.firstOrNull()}")
                }
            }
            report.append("${tree.root.path}: ${tree.files.size + tree.skipped.size} files, $filesWithErrors with errors, $errorCount errors, $stubMismatches stub mismatches")
            if (tree.skipped.isNotEmpty()) report.append(", ${tree.skipped.size} skipped (PHP syntax above the IDE's level ${ideLevel().versionString}): ${tree.skipped.joinToString()}")
            if (phpStormFailsToo.isNotEmpty()) report.append(", ${phpStormFailsToo.size} with PHP PhpStorm itself does not parse: ${phpStormFailsToo.joinToString()}")
            report.append('\n')
            listing.forEach { report.append("  ").append(it).append('\n') }
            if (errorCount + stubMismatches > listing.size) report.append("  … ${errorCount + stubMismatches - listing.size} more\n")
            if (errorCount > 0 || stubMismatches > 0) failed = true
        }
        println(report)
        assertFalse("findings in real Phaenon trees:\n$report", failed)
    }

    /**
     * The type provider on real declarations: every parameter, property and return type whose
     * erased text is a `list<…>` or `map<…>` must carry the pseudo-class that gives it the method
     * syntax. (The trees do not use the method syntax on `list`/`string` receivers yet; the
     * declarations are what exercises [PhaenonTypeProvider] at volume.)
     */
    fun testErasedCollectionDeclarationsInRealTreesGetTheirPseudoClass() {
        val trees = loadTrees() ?: return
        val report = StringBuilder()
        val failures = ArrayList<String>()
        for (tree in trees) {
            var checked = 0
            for (loaded in tree.files) {
                if (checked >= SAMPLE_CAP) break
                val declarations = PsiTreeUtil.collectElementsOfType(loaded.file, PhpNamedElement::class.java)
                    .filter { it is Parameter || it is Field || it is Function }
                for (declaration in declarations) {
                    val erased = when (declaration) {
                        is Function -> PhaenonErasedTypes.returnType(declaration)
                        else -> PhaenonErasedTypes.beforeName(declaration)
                    }
                    val expected = when {
                        erased.matches(LIST) -> PhaenonTypeText.LIST_METHODS
                        erased.matches(MAP) -> PhaenonTypeText.MAP_METHODS
                        else -> continue
                    }
                    checked++
                    val types = (declaration as PhpTypedElement).type.global(project).typesWithParametrisedParts
                    if (types.none { it.startsWith(expected) }) {
                        failures.add("${loaded.relative}:${line(loaded.text, declaration.textOffset)}: `$erased ${declaration.name}` typed as $types, expected $expected")
                    }
                }
            }
            report.append("${tree.root.path}: $checked list/map declarations checked\n")
        }
        println(report)
        assertEmpty("erased declarations without their pseudo-class:\n" + failures.take(LISTING_CAP).joinToString("\n"), failures)
    }

    /**
     * [PhaenonHierarchyInspection] on real hierarchies: every method of the trees against the
     * methods it overrides. The trees pass `phn check`, which reports an incompatible override
     * (`P2502`), so every finding is the plugin misreading an erased signature or a substitution.
     * Classes from `vendor` are not loaded; overrides of them are not checked.
     */
    fun testNoOverrideInRealTreesIsReportedIncompatible() {
        val trees = loadTrees() ?: return
        val report = StringBuilder()
        val findings = ArrayList<String>()
        for (tree in trees) {
            var judged = 0
            for (loaded in tree.files) {
                for (method in PsiTreeUtil.collectElementsOfType(loaded.file, Method::class.java)) {
                    if (!PhaenonHierarchyInspection.owns(method)) continue
                    judged++
                    for ((element, message) in PhaenonHierarchyInspection.problems(method)) {
                        findings.add("${loaded.relative}:${line(loaded.text, element.textOffset)}: $message")
                    }
                }
            }
            report.append("${tree.root.path}: $judged methods with an erased override judged\n")
        }
        println(report)
        assertEmpty("overrides reported incompatible in real Phaenon trees:\n" + findings.take(LISTING_CAP).joinToString("\n"), findings)
    }

    /**
     * Reformat Code on real files, with the PSR-12 style: the formatter works on `.phn`
     * ([PhaenonFormattingModelBuilder]), leaves every erased construct as it was written, keeps a
     * construct that belongs to its neighbour against it (`Box<T>`, `): T`, `string|list<int>`),
     * and breaks no file that parsed. Files a second pass changes again are listed, not counted:
     * PhpStorm's PHP formatter re-indents the arguments of a call it wrapped on the first pass in
     * `.php` files as well (the compiled `SitesApi.php` of tq-cli does it as its `.phn` does).
     */
    fun testRealTreesReformatAroundErasedConstructsAndNeverInsideThem() {
        val trees = loadTrees() ?: return
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        val report = StringBuilder()
        val failures = ArrayList<String>()
        for (tree in trees) {
            var changed = 0
            val unsettled = ArrayList<String>()
            for (loaded in tree.files) {
                val file = loaded.file
                val erasedBefore = erasedTexts(file)
                val errorsBefore = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size
                reformat(file)
                val once = file.text
                if (once != loaded.text) changed++
                if (erasedTexts(file) != erasedBefore) failures.add("${loaded.relative}: an erased construct changed")
                val errorsAfter = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
                if (errorsAfter.size > errorsBefore) failures.add("${loaded.relative}:${line(once, errorsAfter.first().textOffset)}: syntax error after reformatting (near '${near(once, errorsAfter.first().textOffset)}')")
                for (leaf in PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }) {
                    val first = leaf.text.first()
                    val offset = leaf.textRange.startOffset
                    if ((first == '<' || first == ':' || first == '|') && offset > 0 && once[offset - 1].isWhitespace()) {
                        failures.add("${loaded.relative}:${line(once, offset)}: '${leaf.text.take(30)}' moved off its neighbour")
                    }
                }
                reformat(file)
                if (file.text != once) unsettled.add(loaded.relative)
            }
            report.append("${tree.root.path}: ${tree.files.size} files reformatted, $changed changed")
            if (unsettled.isNotEmpty()) report.append(", ${unsettled.size} changed again by a second pass: ${unsettled.joinToString()}")
            report.append('\n')
        }
        println(report)
        assertEmpty("reformatting real Phaenon trees:\n" + failures.take(LISTING_CAP).joinToString("\n"), failures)
    }

    private fun erasedTexts(file: PsiFile): List<String> =
        PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }

    private fun reformat(file: PsiFile) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    // ---------------------------------------------------------------- loading

    private fun loadTrees(): List<Tree>? {
        val value = System.getProperty("phaenon.trees")?.takeIf { it.isNotBlank() } ?: System.getenv("PHAENON_TREES")
        if (value.isNullOrBlank()) {
            println("PhaenonRealTreeTest: PHAENON_TREES is not set, nothing to scan")
            return null
        }
        val roots = value.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { path ->
            File(path.replaceFirst(Regex("^~"), System.getProperty("user.home"))).also { assertTrue("PHAENON_TREES entry is not a directory: $path", it.isDirectory) }
        }
        val beyondIde = syntaxBeyondTheIde()
        return roots.mapIndexed { n, root ->
            val sources = root.walkTopDown()
                .onEnter { it.name != "vendor" && it.name != ".git" }
                .filter { it.isFile && it.extension == PhaenonFiles.EXTENSION }
                .sortedBy { it.path }
                .toList()
            val skipped = ArrayList<String>()
            val files = ArrayList<Loaded>()
            for (source in sources) {
                val relative = source.relativeTo(root).path
                val text = source.readText()
                if (beyondIde.any { it.containsMatchIn(text) }) {
                    skipped.add(relative)
                    continue
                }
                files.add(Loaded(relative, text, myFixture.addFileToProject("tree$n/$relative", text)))
            }
            Tree(root, files, skipped)
        }
    }

    /** Patterns of PHP syntax the IDE's highest language level does not parse yet. */
    private fun syntaxBeyondTheIde(): List<Regex> {
        val level = ideLevel().versionString.split('.').map { it.toIntOrNull() ?: 0 }
        val php85 = level[0] > 8 || level[0] == 8 && level.getOrElse(1) { 0 } >= 5
        return if (php85) emptyList() else listOf(Regex("\\|>"))
    }

    private fun ideLevel(): PhpLanguageLevel = PhpLanguageLevel.values().last()

    /**
     * Whether PhpStorm's own parser, given the file as plain PHP (an open tag in front, no
     * erasure), reports errors at the same offsets. Then the construct is one PhpStorm does not
     * know, not one the plugin erased wrongly.
     */
    private fun failsAsPlainPhp(text: String, errors: Collection<PsiErrorElement>): Boolean {
        val prefix = "<?php\n"
        val php = PsiFileFactory.getInstance(project).createFileFromText("plain.php", PhpFileType.INSTANCE, prefix + text)
        val offsets = PsiTreeUtil.collectElementsOfType(php, PsiErrorElement::class.java).map { it.textOffset - prefix.length }.toSet()
        return errors.all { it.textOffset in offsets }
    }

    private fun line(text: String, offset: Int): Int = StringUtil.offsetToLineNumber(text, offset) + 1

    private fun near(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd).trim().take(80)
    }

    private companion object {
        const val LISTING_CAP = 200
        const val SAMPLE_CAP = 500
        val LIST = Regex("^\\??list<.*")
        val MAP = Regex("^\\??map<.*")
    }
}
