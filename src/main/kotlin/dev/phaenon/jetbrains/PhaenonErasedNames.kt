package dev.phaenon.jetbrains

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpUse

/**
 * The class names inside erased constructs — `list<DomainEvent>`, `Box<User, Role>`,
 * `class<Aggregate>` — and how they resolve: the way PHP would, through the file's `use`
 * statements, then the current namespace, then the global one.
 */
object PhaenonErasedNames {

    /** The words of a Phaenon construct that name no class. */
    private val NOT_A_CLASS: Set<String> = setOf(
        "list", "map", "class", "bytes", "of", "out", "in", "is", "as", "type",
        "int", "float", "string", "bool", "mixed", "null", "void", "never", "object", "array", "callable", "iterable",
        "self", "static", "parent", "true", "false",
    )

    private val NAME: Regex = Regex("""\\?[A-Za-z_][A-Za-z0-9_]*(\\[A-Za-z_][A-Za-z0-9_]*)*""")

    /**
     * Each class name in an erased leaf's text: the range of its last segment, and the name to
     * resolve — as written, except that a bare `Closure` before a signature is `\Closure`, as the
     * compiler reads it whatever the namespace and imports.
     */
    fun classNames(leaf: PsiElement): List<Pair<TextRange, String>> {
        val text = leaf.text
        val typeParameters = PhaenonErasedTypes.typeParametersInScope(leaf)
        val names = ArrayList<Pair<TextRange, String>>()
        for (match in NAME.findAll(text)) {
            val name = match.value
            val plain = name.trimStart('\\')
            if (plain.lowercase() in NOT_A_CLASS || plain in typeParameters) continue
            if (isAliasDeclaration(text, match.range.first)) continue
            val last = name.lastIndexOf('\\') + 1
            val range = TextRange(match.range.first + last, match.range.last + 1)
            names.add(range to if (name == "Closure" && isClosureSignatureAt(text, range.endOffset)) "\\Closure" else name)
        }
        return names
    }

    /** Whether a closure signature `(…)` starts at [at], whitespace aside. */
    private fun isClosureSignatureAt(text: String, at: Int): Boolean {
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '('
    }

    /** Each use of a type parameter in scope in an erased leaf's text: its range and name. */
    fun typeParameters(leaf: PsiElement): List<Pair<TextRange, String>> {
        val typeParameters = PhaenonErasedTypes.typeParametersInScope(leaf)
        if (typeParameters.isEmpty()) return emptyList()
        return NAME.findAll(leaf.text)
            .filter { it.value in typeParameters }
            .map { TextRange(it.range.first, it.range.last + 1) to it.value }
            .toList()
    }

    /** `type UserMap = …;` declares `UserMap`; it is not a reference. */
    private fun isAliasDeclaration(text: String, at: Int): Boolean {
        val before = text.substring(0, at).trimEnd()
        return before.endsWith("type") && (before.length == 4 || !before[before.length - 5].isLetterOrDigit())
    }

    /** The classes, interfaces and traits a name written in context resolves to; empty for keywords and type parameters. */
    fun resolve(name: String, context: PsiElement): List<PhpClass> {
        if (name.trimStart('\\').lowercase() in NOT_A_CLASS) return emptyList()
        val index = PhpIndex.getInstance(context.project)
        if (name.startsWith("\\")) return index.getAnyByFQN(name).toList()
        val first = name.substringBefore('\\')
        val rest = name.substring(first.length)
        imports(context.containingFile)[first.lowercase()]?.let { return index.getAnyByFQN(it + rest).toList() }
        val namespace = PsiTreeUtil.getParentOfType(context, PhpNamespace::class.java)?.fqn?.trim('\\').orEmpty()
        val inNamespace = if (namespace.isEmpty()) "\\$name" else "\\$namespace\\$name"
        index.getAnyByFQN(inNamespace).takeIf { it.isNotEmpty() }?.let { return it.toList() }
        return index.getAnyByFQN("\\$name").toList()
    }

    /**
     * The namespace a qualifier written in context names, without leading or trailing backslash:
     * `\App\Event` as written, `Event` through an import or the current namespace, else global.
     */
    fun resolveNamespace(qualifier: String, context: PsiElement): String? {
        if (qualifier.isEmpty()) return null
        if (qualifier.startsWith("\\")) return qualifier.trim('\\')
        val first = qualifier.substringBefore('\\')
        val rest = qualifier.substring(first.length)
        imports(context.containingFile)[first.lowercase()]?.let { return (it + rest).trim('\\') }
        val namespace = PsiTreeUtil.getParentOfType(context, PhpNamespace::class.java)?.fqn?.trim('\\').orEmpty()
        if (namespace.isEmpty()) return qualifier
        val index = PhpIndex.getInstance(context.project)
        val inNamespace = "$namespace\\$qualifier"
        val known = index.getAllChildNamespacesFqns("\\$namespace\\").any { it.trim('\\').equals(inNamespace, ignoreCase = true) } ||
            index.getAllClassFqns(PlainPrefixMatcher("\\$inNamespace\\", true)).isNotEmpty()
        return if (known) inNamespace else qualifier
    }

    /**
     * Whether an erased construct of the file uses [import]: its short name (the alias, else the
     * last segment) appears in one. What PhpStorm cannot see there counts as a use, as it does for
     * `phn check` (P2013); the unused-import inspection and Optimize Imports both go by this.
     */
    fun isUsedByErasedConstruct(import: PhpUse): Boolean {
        val short = import.aliasName ?: import.fqn.substringAfterLast('\\')
        return short.isNotEmpty() && mentions(import.containingFile, short)
    }

    /** Whether the short name of an import appears in an erased construct anywhere in the file. */
    fun mentions(file: PsiFile, shortName: String): Boolean =
        CachedValuesManager.getCachedValue(file) {
            val words = HashSet<String>()
            for (leaf in PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)) {
                if (!PhaenonTokenTypes.isErased(leaf)) continue
                for (match in NAME.findAll(leaf.text)) words.add(match.value.trimStart('\\').substringBefore('\\').lowercase())
            }
            CachedValueProvider.Result.create(words, file)
        }.contains(shortName.lowercase())

    /** Lower-cased short name → FQN of every class import of the file. */
    fun imports(file: PsiFile): Map<String, String> =
        CachedValuesManager.getCachedValue(file) {
            val map = HashMap<String, String>()
            for (use in PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java)) {
                if (use.isOfConst || use.isOfFunction || use.isTraitImport) continue
                val fqn = use.fqn.takeIf { it.isNotEmpty() } ?: continue
                val short = use.aliasName ?: fqn.substringAfterLast('\\')
                map[short.lowercase()] = if (fqn.startsWith("\\")) fqn else "\\$fqn"
            }
            CachedValueProvider.Result.create(map, file)
        }
}
