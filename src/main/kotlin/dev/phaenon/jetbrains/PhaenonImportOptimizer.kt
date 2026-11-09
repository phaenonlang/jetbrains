package dev.phaenon.jetbrains

import com.intellij.lang.ImportOptimizer
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.PhpGroupUseElement
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.jetbrains.php.refactoring.PhpAliasImporter

/**
 * Optimize Imports in a `.phn` file: PhpStorm's optimizer, without removing the imports that only
 * erased constructs use.
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
 * The block is laid out by the PHP code style, as in a `.php` file: the statements the optimizer
 * and this class insert are reformatted at the end of the command by PhpStorm's formatter, which
 * runs on `.phn` files through [PhaenonFormattingModelBuilder].
 */
class PhaenonImportOptimizer : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean = file is PhpFile && PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)

    override fun processFile(file: PsiFile): Runnable {
        val php = LanguageImportStatements.INSTANCE.allForLanguage(PhpLanguage.INSTANCE)
            .firstOrNull { it !is PhaenonImportOptimizer && it.supports(file) }
            ?: return EmptyRunnable.getInstance()
        val phpFile = file as PhpFile
        return Optimizing(phpFile, php.processFile(file), importsUsedByErasedConstructs(phpFile), countImports(phpFile))
    }

    /** An import as PhpStorm writes it back, and the scope it belongs to. */
    private data class Import(val scope: SmartPsiElementPointer<PhpPsiElement>, val keyword: String?, val fqn: String, val alias: String?)

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

    /** Runs PhpStorm's optimizer and inserts again what it removed of [used]. */
    private class Optimizing(
        private val file: PhpFile,
        private val optimize: Runnable,
        private val used: List<Import>,
        private val before: Int,
    ) : ImportOptimizer.CollectingInfoRunnable {

        private var removed = 0

        override fun run() {
            optimize.run()
            for (import in used) {
                val scope = import.scope.element ?: continue
                if (!isPresent(scope, import)) insert(scope, import)
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
         * [import] put back as PhpStorm's auto-import writes one: before the first import the
         * optimizer's ordering puts after it, or where PhpStorm puts a first import when the scope
         * has none left.
         */
        private fun insert(scope: PhpPsiElement, import: Import) {
            PhpAliasImporter.insertUseStatementWithKeyword(import.keyword, import.fqn, import.alias, scope)
        }
    }

    private companion object {
        /** Where imports live, as PhpStorm's optimizer walks them: the file, then each namespace. */
        fun scopes(file: PhpFile): List<PhpPsiElement> = listOf<PhpPsiElement>(file) + PhpCodeInsightUtil.collectNamespaces(file)

        fun countImports(file: PhpFile): Int = scopes(file).sumOf { scope -> PhpCodeInsightUtil.collectImports(scope).sumOf { it.declarations.size } }
    }
}
