package dev.phaenon.jetbrains

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
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
 * `use` statement on insertion, as it would in PHP code. After a qualifier (`Event\Dom`,
 * `\App\Event\Dom`) the members of that namespace are offered, written as qualified.
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
            val prefix = text.substring(start, at)
            val matcher = result.prefixMatcher.cloneWithPrefix(prefix)
            val out = result.withPrefixMatcher(matcher)
            if (start > 0 && text[start - 1] == '\\') {
                // `Event\Dom`, `\App\Event\Dom`: the members of the namespace the qualifier names.
                var from = start - 1
                while (from > 0 && (text[from - 1].isLetterOrDigit() || text[from - 1] == '_' || text[from - 1] == '\\')) from--
                val namespace = PhaenonErasedNames.resolveNamespace(text.substring(from, start - 1), leaf) ?: return
                qualifiedMembers(namespace, prefix, leaf.project, out)
                return
            }

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

/** The classes, interfaces, traits and child namespaces directly under [namespace] (no leading or trailing backslash). */
private fun qualifiedMembers(namespace: String, prefix: String, project: Project, out: CompletionResultSet) {
    val index = PhpIndex.getInstance(project)
    val qualified = "\\$namespace\\"
    val fqnMatcher = PlainPrefixMatcher(qualified + prefix, true)
    val members = LinkedHashSet<String>()
    for (fqn in index.getAllClassFqns(fqnMatcher) + index.getAllInterfacesFqns(fqnMatcher)) {
        val rest = fqn.substring(qualified.length)
        if (rest.isNotEmpty() && '\\' !in rest) members.add(rest)
    }
    for (name in index.allTraitNames) {
        if (index.getTraitsByName(name).any { it.namespaceName.equals(qualified, ignoreCase = true) }) members.add(name)
    }
    for (name in members) {
        val classes = index.getAnyByFQN(qualified + name)
        val klass = classes.firstOrNull { !PhaenonFiles.isGeneratedOutput(it.containingFile) } ?: classes.firstOrNull()
        out.addElement(
            klass?.let { LookupElementBuilder.create(it, name).withIcon(it.getIcon(0)) } ?: LookupElementBuilder.create(name),
        )
    }
    for (child in index.getAllChildNamespacesFqns(qualified)) {
        val short = child.trim('\\').substringAfterLast('\\')
        if (short.isNotEmpty()) out.addElement(LookupElementBuilder.create(short).withTypeText("namespace", true))
    }
}
