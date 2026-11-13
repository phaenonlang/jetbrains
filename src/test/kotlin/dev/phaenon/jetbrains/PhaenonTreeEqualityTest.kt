package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.parser.PhpElementTypes
import java.io.File

/**
 * The tree of a `.phn` file is PhpStorm's own: PHP that reads the same with and without its open
 * tag parses, as a `.phn`, into the tree PhpStorm builds of the `.php` once its lazy bodies are
 * expanded — the same element types over the same ranges, the same errors at the same places.
 *
 * This is the guard of [PhaenonPsiBuilder]'s bodies. They are parsed by running PhpStorm's parser
 * again on the builder, fenced at the body's end, which rests on how that parser drives a builder
 * (the markers it opens around its statements, its statement loop, the collapse of a body into a
 * lazy node) and on nothing PhpStorm promises: were one of them to change, the tree of a body
 * would come out wrong and nothing would say so. Here it says so. Run it against every new
 * PhpStorm build (README, "Build").
 *
 * The two trees differ in what the comparison leaves out: the open tag and the line break after
 * it, the file's own node, and a body's node type — PhpStorm's lazy group statement in the `.php`,
 * the non-lazy one in the `.phn`, which every feature reads alike
 * (`PhpElementTypes.ANY_GROUP_STATEMENT`).
 *
 * `testData/trees` holds the corpus: valid PHP 8.5 in its `.php` files (bodies in bodies, closures,
 * anonymous classes, `try`, `match`, heredocs, attributes, enums, property hooks, functions
 * declared in functions, inline HTML), and code as it stands while it is typed in those of
 * `broken` (braces missing and left over, statements cut short, a file that ends in a body).
 *
 * Two shapes of broken code are known to differ and are not in the corpus. Both come from what
 * PhpStorm expands a lazy body from, the body's text with its braces, where the plugin's parser
 * of a body stops before the closing brace. A `namespace Name;` inside a body, which is no PHP,
 * runs to the end of that text and takes the body's `}` for a statement of its own in PhpStorm's
 * tree; in the `.phn`'s the brace closes the body. And a `}` between a body's statements that the
 * count of braces did not take for the body's end (an interpolation `{$x` cut short before it)
 * closes the body without a word in PhpStorm's tree, the statements after it still its children;
 * in the `.phn`'s a statement is expected where it stands.
 *
 * `PHAENON_PHP_TREES` names directories of PHP to compare the same way, for volume:
 *
 * ```sh
 * PHAENON_PHP_TREES=/path/to/a/php/project:/path/to/another ./gradlew test --tests '*TreeEquality*'
 * ```
 */
class PhaenonTreeEqualityTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    fun testValidPhpParsesIntoPhpStormsTree() {
        val fixtures = fixtures("trees")
        assertTrue("fixtures under testData/trees", fixtures.size >= 5)
        val differing = ArrayList<String>()
        for (fixture in fixtures) {
            val source = code(fixture.readText()) ?: error("${fixture.name} does not begin with an open tag")
            val (php, phn) = parse(fixture.name, source)
            assertEquals("${fixture.name}: valid PHP has no syntax error", emptyList<String>(), errors(phn))
            assertTrue("${fixture.name}: the comparison covers bodies", bodies(phn) > 0)
            difference(tree(php, PREFIX.length), tree(phn, 0))?.let { differing.add("${fixture.name}: $it") }
        }
        assertEmpty("valid PHP whose .phn tree is not PhpStorm's:\n" + differing.joinToString("\n"), differing)
    }

    fun testBrokenPhpParsesIntoPhpStormsTree() {
        val fixtures = fixtures("trees/broken")
        assertTrue("fixtures under testData/trees/broken", fixtures.size >= 5)
        val differing = ArrayList<String>()
        for (fixture in fixtures) {
            val source = code(fixture.readText()) ?: error("${fixture.name} does not begin with an open tag")
            val (php, phn) = parse(fixture.name, source)
            assertTrue("${fixture.name}: broken PHP has syntax errors", errors(phn).isNotEmpty())
            difference(tree(php, PREFIX.length), tree(phn, 0))?.let { differing.add("${fixture.name}: $it") }
        }
        assertEmpty("broken PHP whose .phn tree is not PhpStorm's:\n" + differing.joinToString("\n"), differing)
    }

    /**
     * The corpus under the language levels a project may have set below Phaenon's floor, where
     * much of it is a syntax error: PhpStorm's recovery is the `.phn`'s as well. The levels start
     * at 8.0. Below it PhpStorm's parser reads `#[` as a comment to the end of its line, a body's
     * closing brace on that line included, which the body keeps in a `.phn`; below 7.4 it reads an
     * `fn` it has skipped over as a name, and back as `fn` only where the body is lexed anew.
     */
    fun testTheTreeIsPhpStormsAtEveryLanguageLevelFromPhp8() {
        val sources = (fixtures("trees") + fixtures("trees/broken")).map { it.name to code(it.readText())!! }
        val levels = PhpLanguageLevel.values().filter { it.isAtLeast(PhpLanguageLevel.PHP800) }
        assertTrue("language levels from PHP 8.0", levels.size >= 6)
        val differing = ArrayList<String>()
        for (level in levels) {
            PhpProjectConfigurationFacade.getInstance(project).languageLevel = level
            for ((name, source) in sources) {
                val (php, phn) = parse(name, source)
                difference(tree(php, PREFIX.length), tree(phn, 0))?.let { differing.add("PHP ${level.versionString}, $name: $it") }
            }
        }
        assertEmpty("PHP whose .phn tree is not PhpStorm's:\n" + differing.joinToString("\n"), differing)
    }

    fun testNoBodyOfAPhaenonFileIsLazy() {
        for (fixture in fixtures("trees") + fixtures("trees/broken")) {
            val (_, phn) = parse(fixture.name, code(fixture.readText())!!)
            assertEquals("${fixture.name}: lazy bodies", 0, count(phn.node) { it.elementType === PhpElementTypes.GROUP_STATEMENT })
        }
    }

    /** Every PHP file under the directories `PHAENON_PHP_TREES` names; passes trivially when it is unset. */
    fun testRealPhpTreesParseIntoPhpStormsTrees() {
        val value = System.getProperty("phaenon.phpTrees")?.takeIf { it.isNotBlank() } ?: System.getenv("PHAENON_PHP_TREES")
        if (value.isNullOrBlank()) {
            println("PhaenonTreeEqualityTest: PHAENON_PHP_TREES is not set, nothing to compare")
            return
        }
        val report = StringBuilder("PhpStorm ${ApplicationInfo.getInstance().build.asString()}\n")
        val differing = ArrayList<String>()
        for (path in value.split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            val root = File(path.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            assertTrue("PHAENON_PHP_TREES entry is not a directory: $path", root.isDirectory)
            var compared = 0
            var skipped = 0
            var bodies = 0
            var withErrors = 0
            val sources = root.walkTopDown().onEnter { it == root || it.name != ".git" }.filter { it.isFile && it.extension == "php" }.sortedBy { it.path }
            for (file in sources) {
                // PHP from its first byte, and nothing but PHP: a script with a shebang or a template is no `.phn`.
                val source = code(file.readText())?.takeIf { TAG !in it }
                if (source == null) {
                    skipped++
                    continue
                }
                val (php, phn) = parse(file.name, source)
                compared++
                bodies += bodies(phn)
                if (errors(phn).isNotEmpty()) withErrors++
                difference(tree(php, PREFIX.length), tree(phn, 0))?.let { differing.add("${file.path}: $it") }
            }
            report.append("${root.path}: $compared files compared, $bodies bodies, $withErrors with syntax errors, $skipped skipped (no open tag at the start, or tags inside)\n")
        }
        println(report)
        assertEmpty(
            "PHP files whose .phn tree is not PhpStorm's (${differing.size}):\n$report" + differing.take(LISTING_CAP).joinToString("\n"),
            differing,
        )
    }

    // ---------------------------------------------------------------- the two trees

    /** The code of a PHP file: what follows its open tag, or null when it does not begin with one. */
    private fun code(text: String): String? = if (text.startsWith(OPEN_TAG)) text.removePrefix(OPEN_TAG).trimStart() else null

    /** [source] as PhpStorm parses it in a `.php`, behind an open tag, and as the plugin does in a `.phn`. */
    private fun parse(name: String, source: String): Pair<PsiFile, PsiFile> {
        val factory = PsiFileFactory.getInstance(project)
        val base = name.removeSuffix(".php")
        val php = factory.createFileFromText("$base.php", PhpFileType.INSTANCE, PREFIX + source)
        val phn = factory.createFileFromText("$base.phn", PhpFileType.INSTANCE, source)
        assertSame("$base.phn must parse through the Phaenon root", PhaenonFileElementType, phn.node.elementType)
        assertNotSame("$base.php must parse as PhpStorm parses it", PhaenonFileElementType, php.node.elementType)
        assertEquals(source, phn.text)
        return php to phn
    }

    /**
     * The tree below [file], one node a line: type, range less [shift], a leaf's text, an error's
     * message. Walking it expands PhpStorm's lazy bodies. The file's top statement list is left
     * out and its children stand in its place: the open tag is inside it in a `.php`, and what
     * comes before the first statement of a `.phn` is outside.
     */
    private fun tree(file: PsiFile, shift: Int): String {
        val out = StringBuilder()
        fun walk(node: ASTNode, depth: Int, top: Boolean) {
            val range = node.textRange
            // The open tag and the line break after it.
            if (range.endOffset <= shift) return
            val type = node.elementType
            if (top && type === PhpElementTypes.NON_LAZY_GROUP_STATEMENT) {
                var child = node.firstChildNode
                while (child != null) {
                    walk(child, depth, false)
                    child = child.treeNext
                }
                return
            }
            out.append("  ".repeat(depth))
            out.append(if (type === PhpElementTypes.GROUP_STATEMENT) PhpElementTypes.NON_LAZY_GROUP_STATEMENT else type)
            out.append(' ').append(range.startOffset - shift).append('-').append(range.endOffset - shift)
            val psi = node.psi
            if (psi is PsiErrorElement) out.append(" error: ").append(psi.errorDescription)
            val first = node.firstChildNode
            if (first == null && psi !is PsiErrorElement) out.append(" '").append(node.text.replace("\n", "\\n")).append('\'')
            out.append('\n')
            var child = first
            while (child != null) {
                walk(child, depth + 1, false)
                child = child.treeNext
            }
        }
        var child = file.node.firstChildNode
        while (child != null) {
            walk(child, 0, true)
            child = child.treeNext
        }
        return out.toString()
    }

    private fun errors(file: PsiFile): List<String> {
        val out = ArrayList<String>()
        count(file.node) { node -> (node.psi as? PsiErrorElement)?.also { out.add("${it.errorDescription} at ${it.textOffset}") } != null }
        return out
    }

    /** The bodies of [file]: the group statements below its top statement list. */
    private fun bodies(file: PsiFile): Int = count(file.node) { it.elementType === PhpElementTypes.NON_LAZY_GROUP_STATEMENT } - 1

    private fun count(node: ASTNode, matches: (ASTNode) -> Boolean): Int {
        var n = if (matches(node)) 1 else 0
        var child = node.firstChildNode
        while (child != null) {
            n += count(child, matches)
            child = child.treeNext
        }
        return n
    }

    /** Where two trees part, with the nodes around it; null when they are one. */
    private fun difference(expected: String, actual: String): String? {
        if (expected == actual) return null
        val a = expected.lines()
        val b = actual.lines()
        val at = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
        fun around(lines: List<String>) = lines.subList(maxOf(0, at - CONTEXT), minOf(lines.size, at + CONTEXT + 1)).joinToString("\n") { "    $it" }
        return "the trees part at node ${at + 1}\n  PhpStorm's of the .php:\n${around(a)}\n  the .phn's:\n${around(b)}"
    }

    private fun fixtures(directory: String): List<File> =
        File(testDataPath, directory).listFiles { file -> file.isFile && file.extension == "php" }.orEmpty().sortedBy { it.name }

    private companion object {
        const val OPEN_TAG = "<?php"
        /** What stands before the code in the `.php`: the open tag on a line of its own. */
        const val PREFIX = "$OPEN_TAG\n"
        val TAG = Regex("<\\?|\\?>")
        const val LISTING_CAP = 50
        /** The nodes shown before and after the one two trees part at. */
        const val CONTEXT = 4
    }
}
