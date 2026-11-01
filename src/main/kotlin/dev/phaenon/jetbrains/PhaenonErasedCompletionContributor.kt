package dev.phaenon.jetbrains

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.refactoring.PhpAliasImporter

/**
 * Completion inside an erased construct — the type arguments of `list<…>`, `map<…>`, `Box<…>`,
 * `class<…>`, a tuple, a closure signature: the classes, interfaces and traits of the project,
 * the type parameters in scope, and the type keywords. A class from another namespace gets its
 * `use` statement on insertion, as it would in PHP code.
 *
 * PhpStorm sees the construct as a comment, so its own completion offers nothing there.
 */
class PhaenonErasedCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(PhaenonTokenTypes.ERASED), Provider)
    }

    override fun beforeCompletion(context: CompletionInitializationContext) {
        // The default dummy identifier ends in a space, which would stop `<Dom…>` scanning as a
        // type list in the copy the completion works on.
        if (!PhaenonFiles.isPhaenonFile(context.file.viewProvider.virtualFile)) return
        val at = context.file.findElementAt(context.startOffset)
        val before = if (context.startOffset > 0) context.file.findElementAt(context.startOffset - 1) else null
        if (PhaenonTokenTypes.isErased(at) || PhaenonTokenTypes.isErased(before)) {
            context.dummyIdentifier = CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED
        }
    }

    private object Provider : CompletionProvider<CompletionParameters>() {

        private val KEYWORDS: List<String> = listOf(
            "int", "float", "string", "bool", "mixed", "null", "void", "never", "object", "array", "callable", "iterable",
            "bytes", "list", "map", "class", "self", "static", "true", "false",
        )

        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val leaf = parameters.position
            val text = leaf.text
            val at = (parameters.offset - leaf.textRange.startOffset).coerceIn(0, text.length)
            var start = at
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
            if (start > 0 && text[start - 1] == '\\') return // a qualified name: not completed here
            val prefix = text.substring(start, at)
            val matcher = result.prefixMatcher.cloneWithPrefix(prefix)
            val out = result.withPrefixMatcher(matcher)

            for (name in PhaenonErasedTypes.typeParametersInScope(leaf)) {
                out.addElement(LookupElementBuilder.create(name).bold().withTypeText("type parameter", true))
            }
            for (word in KEYWORDS) {
                out.addElement(LookupElementBuilder.create(word).bold())
            }
            val index = PhpIndex.getInstance(leaf.project)
            val names = LinkedHashSet<String>()
            names.addAll(index.getAllClassNames(matcher))
            names.addAll(index.allInterfaceNames.filter(matcher::prefixMatches))
            names.addAll(index.allTraitNames.filter(matcher::prefixMatches))
            for (name in names) {
                val classes = index.getClassesByName(name) + index.getInterfacesByName(name) + index.getTraitsByName(name)
                for (klass in classes) {
                    if (PhaenonFiles.isGeneratedOutput(klass.containingFile)) continue
                    out.addElement(
                        LookupElementBuilder.create(klass, klass.name)
                            .withIcon(klass.getIcon(0))
                            .withTailText(" " + klass.namespaceName.trim('\\'), true)
                            .withInsertHandler(ImportingInsertHandler(klass)),
                    )
                }
            }
        }
    }

    /** After the short name goes in, the `use` statement follows unless the class needs none. */
    private class ImportingInsertHandler(private val klass: PhpClass) : InsertHandler<LookupElement> {

        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            context.commitDocument()
            val file = context.file
            val at = file.findElementAt(context.startOffset) ?: return
            val fqn = klass.fqn
            val namespace = PsiTreeUtil.getParentOfType(at, PhpNamespace::class.java)?.fqn?.trim('\\').orEmpty()
            if (klass.namespaceName.trim('\\') == namespace) return
            when (PhaenonErasedNames.imports(file)[klass.name.lowercase()]) {
                fqn -> return
                null -> {
                    val scope = PhpCodeInsightUtil.findScopeForUseOperator(at) ?: return
                    PhpAliasImporter.insertUseStatement(fqn, scope)
                }
                else -> context.document.replaceString(context.startOffset, context.tailOffset, fqn)
            }
        }
    }
}
