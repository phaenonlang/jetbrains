package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.codeInsight.PhpImportOptimizer
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.PhpCodeStyleSettings
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.PhpGroupUseElement
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.jetbrains.php.lang.psi.elements.PhpUseList
import com.jetbrains.php.refactoring.PhpAliasImporter

/**
 * Optimize Imports in a `.phn` file: PhpStorm's optimizer, without removing the imports that only
 * erased constructs use, and with one import per line.
 *
 * PhpStorm's optimizer keeps an import when PHP's own references use it: class, function and
 * constant references, namespace references, docblock types (`PhpUnusedAliasInspection.filterUnused`).
 * A class named only in an erased construct, `DomainEvent` in `list<DomainEvent>`, sits in a
 * comment leaf to it, so the import was "unused" and went, and the file no longer compiled. The
 * platform asks the first optimizer of the language that supports a file
 * (`LanguageImportStatements.forFile`), and every way imports get optimized goes through that
 * registry: the Optimize Imports action, Reformat Code with imports, the save action and the
 * pre-commit step (the PHP plugin has no import optimization on the fly). This optimizer comes
 * first for `.phn` files, runs PhpStorm's, and puts back each import an erased construct uses
 * ([PhaenonErasedNames.isUsedByErasedConstruct], the rule the unused-import inspection follows in
 * [PhaenonHighlightFilter]) where the optimizer's sorting puts it.
 *
 * Put back rather than spared: PhpStorm's optimizer decides what to keep in one pass over the
 * file and rebuilds the whole block, and nothing in that pass can be told about erased text. An
 * import of a class in the file's own namespace that only an erased construct names is put back
 * too, although PHP needs none: the inspection does not flag it either.
 *
 * The block is laid out here because nothing else does it in a `.phn` file: PhpStorm's PHP
 * formatter is organised around the `<?php` tags a Phaenon file does not have and changes nothing
 * in one, so the statements the optimizer inserts would stay on one line. Each import goes on a
 * line of its own, class, function and constant imports keep the blank lines between them, and
 * the whitespace before and after the block stays what it was.
 */
class PhaenonImportOptimizer : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean = file is PhpFile && PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)

    override fun processFile(file: PsiFile): Runnable {
        val php = LanguageImportStatements.INSTANCE.allForLanguage(PhpLanguage.INSTANCE)
            .firstOrNull { it !is PhaenonImportOptimizer && it.supports(file) }
            ?: return EmptyRunnable.getInstance()
        val phpFile = file as PhpFile
        return Optimizing(phpFile, php.processFile(file), importsUsedByErasedConstructs(phpFile), layouts(phpFile), countImports(phpFile))
    }

    /** An import as PhpStorm writes it back, and the scope it belongs to. */
    private data class Import(val scope: SmartPsiElementPointer<PhpPsiElement>, val keyword: String?, val fqn: String, val alias: String?)

    /**
     * A scope's import block before the optimizer ran: its first statement, which the optimizer
     * replaces when it rebuilds the block, the whitespace around the block, the leaf before it, and
     * the blank lines it had where class, function and constant imports meet (null: nowhere).
     */
    private class Layout(
        val scope: SmartPsiElementPointer<PhpPsiElement>,
        val first: PhpUseList,
        val before: String,
        val after: String,
        val anchor: SmartPsiElementPointer<PsiElement>?,
        val blankLinesBetweenKinds: Int?,
    )

    private fun importsUsedByErasedConstructs(file: PhpFile): List<Import> {
        val pointers = SmartPointerManager.getInstance(file.project)
        val used = LinkedHashSet<Import>()
        for (scope in scopes(file)) {
            for (list in PhpCodeInsightUtil.collectImports(scope)) {
                for (import in list.declarations) {
                    if (!PhaenonErasedNames.isUsedByErasedConstruct(import)) continue
                    val home = PhpCodeInsightUtil.findScopeForUseOperator(import) ?: scope
                    used.add(Import(pointers.createSmartPsiElementPointer(home), PhpGroupUseElement.getKeyword(import, list), import.fqn, import.aliasName))
                }
            }
        }
        return used.toList()
    }

    private fun layouts(file: PhpFile): List<Layout> {
        val pointers = SmartPointerManager.getInstance(file.project)
        return scopes(file).mapNotNull { scope ->
            val lists = ordered(scope)
            if (lists.isEmpty()) return@mapNotNull null
            val before = PsiTreeUtil.prevLeaf(lists.first())
            val after = PsiTreeUtil.nextLeaf(lists.last())
            val anchor = PsiTreeUtil.prevLeaf(lists.first(), true)?.let { if (it is PsiWhiteSpace) PsiTreeUtil.prevLeaf(it, true) else it }
            val blankLinesBetweenKinds = lists.zipWithNext().firstOrNull { (a, b) -> kind(a) != kind(b) }?.let { (a, _) ->
                ((PsiTreeUtil.nextLeaf(a) as? PsiWhiteSpace)?.text.orEmpty().count { it == '\n' } - 1).coerceAtLeast(0)
            }
            Layout(
                pointers.createSmartPsiElementPointer(scope),
                lists.first(),
                (before as? PsiWhiteSpace)?.text.orEmpty(),
                (after as? PsiWhiteSpace)?.text.orEmpty(),
                anchor?.let(pointers::createSmartPsiElementPointer),
                blankLinesBetweenKinds,
            )
        }
    }

    /** Runs PhpStorm's optimizer, inserts again what it removed of [used], and lays out the blocks it rebuilt. */
    private class Optimizing(
        private val file: PhpFile,
        private val optimize: Runnable,
        private val used: List<Import>,
        private val layouts: List<Layout>,
        private val before: Int,
    ) : ImportOptimizer.CollectingInfoRunnable {

        private var removed = 0

        override fun run() {
            optimize.run()
            for (import in used) {
                val scope = import.scope.element ?: continue
                if (!isPresent(scope, import)) insert(scope, import)
            }
            for (layout in layouts) {
                // A first statement still in place means the optimizer left the block alone.
                if (!layout.first.isValid) lay(layout)
            }
            removed = before - countImports(file)
        }

        /** PhpStorm's own wording, counted after the imports went back in. */
        override fun getUserNotificationInfo(): String =
            if (removed > 0) PhpBundle.message("hint.text.use.statement.choice.s.removed", removed, if (removed > 1) 0 else 1)
            else PhpBundle.message("hint.text.use.statements.checked.nothing.was.removed", before)

        private fun isPresent(scope: PhpPsiElement, import: Import): Boolean =
            PhpCodeInsightUtil.collectImports(scope).any { list ->
                list.declarations.any { it.fqn.equals(import.fqn, ignoreCase = true) && it.aliasName == import.alias && PhpGroupUseElement.getKeyword(it, list) == import.keyword }
            }

        /**
         * [import] as a statement of its own, before the first import the optimizer's ordering puts
         * after it. A scope left without imports gets its block where PhpStorm puts a first import.
         */
        private fun insert(scope: PhpPsiElement, import: Import) {
            val lists = ordered(scope)
            if (lists.isEmpty()) {
                PhpAliasImporter.insertUseStatementWithKeyword(import.keyword, import.fqn, import.alias, scope)
                return
            }
            val statement = PhpPsiElementFactory.createUseStatementWithKeyword(scope.project, import.keyword, import.fqn, import.alias)
            val order = PhpImportOptimizer.getComparator(scope.containingFile)
            val element = PhpAliasImporter.getGroupUseElement(statement)
            val next = element?.let { lists.firstOrNull { list -> PhpAliasImporter.getGroupUseElement(list)?.let { order.compare(element, it) < 0 } == true } }
            if (next != null) next.parent.addBefore(statement, next) else lists.last().let { it.parent.addAfter(statement, it) }
        }

        /**
         * One import per line, indented as the first was; where class, function and constant
         * imports meet (PSR-12's import blocks) the blank lines the block had there, else the PHP
         * code style's; the block's surroundings as they were.
         */
        private fun lay(layout: Layout) {
            val scope = layout.scope.element ?: return
            val lists = ordered(scope)
            if (lists.isEmpty()) {
                // The whole block went: what separated it from its neighbours separates them now.
                val anchor = layout.anchor?.element ?: return
                (PsiTreeUtil.nextLeaf(anchor) as? PsiWhiteSpace)?.let { setText(it, layout.after) }
                return
            }
            val indent = if ('\n' in layout.before) layout.before.substringAfterLast('\n') else ""
            val blankLines = layout.blankLinesBetweenKinds
                ?: CodeStyle.getCustomSettings(file, PhpCodeStyleSettings::class.java).BLANK_LINES_BETWEEN_IMPORTS.coerceAtLeast(0)
            for ((previous, next) in lists.zipWithNext()) {
                val newKind = kind(previous) != kind(next)
                val text = "\n".repeat(if (newKind) blankLines + 1 else 1) + indent
                val between = PsiTreeUtil.nextLeaf(previous)
                when {
                    between is PsiWhiteSpace && PsiTreeUtil.nextLeaf(between)?.let { PsiTreeUtil.isAncestor(next, it, false) } == true -> setText(between, text)
                    between != null && PsiTreeUtil.isAncestor(next, between, false) && previous.parent == next.parent ->
                        previous.parent.addAfter(whitespace(scope, text), previous)
                }
            }
            (PsiTreeUtil.prevLeaf(lists.first()) as? PsiWhiteSpace)?.let { setText(it, layout.before) }
            val after = PsiTreeUtil.nextLeaf(lists.last())
            when {
                after is PsiWhiteSpace -> setText(after, layout.after)
                layout.after.isNotEmpty() -> lists.last().parent.addAfter(whitespace(scope, layout.after), lists.last())
            }
        }

        private fun setText(whitespace: PsiWhiteSpace, text: String) {
            if (text.isNotEmpty() && whitespace.text != text) (whitespace.node as LeafElement).replaceWithText(text)
        }

        private fun whitespace(context: PsiElement, text: String): PsiElement = PsiParserFacade.getInstance(context.project).createWhiteSpaceFromText(text)
    }

    private companion object {
        /** Where imports live, as PhpStorm's optimizer walks them: the file, then each namespace. */
        fun scopes(file: PhpFile): List<PhpPsiElement> = listOf<PhpPsiElement>(file) + PhpCodeInsightUtil.collectNamespaces(file)

        /** Whether a statement imports classes, functions or constants: `null`, `function`, `const` as PhpStorm spells it. */
        fun kind(list: PhpUseList): String? = PhpAliasImporter.getGroupUseElement(list)?.keyword

        /** The import statements of [scope] in document order. */
        fun ordered(scope: PhpPsiElement): List<PhpUseList> = PhpCodeInsightUtil.collectImports(scope).sortedBy { it.textRange.startOffset }

        fun countImports(file: PhpFile): Int = scopes(file).sumOf { scope -> PhpCodeInsightUtil.collectImports(scope).sumOf { it.declarations.size } }
    }
}
