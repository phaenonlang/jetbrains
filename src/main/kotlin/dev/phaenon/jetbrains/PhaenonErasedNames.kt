package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpUse

/**
 * The class names inside erased Phaenon types — `list<DomainEvent>`, `Box<User, Role>`,
 * `class<Aggregate>` — are plain text to PhpStorm: the type was erased into whitespace, so no
 * reference exists for them. This resolves such a name the way PHP would (the file's `use`
 * statements, then the current namespace), for navigation and for the unused-import inspection.
 */
object PhaenonErasedNames {

    /** The words of a Phaenon type that name no class. */
    private val NOT_A_CLASS: Set<String> = setOf(
        "list", "map", "class", "bytes", "of", "out", "in",
        "int", "float", "string", "bool", "mixed", "null", "void", "never", "object", "array", "callable", "iterable",
        "self", "static", "parent", "true", "false",
    )

    /** The identifier under offset in an erased leaf's text, `null` when offset is not on one. */
    fun identifierAt(leaf: PsiWhiteSpace, offset: Int): String? {
        val text = leaf.text
        val at = offset - leaf.textRange.startOffset
        if (at < 0 || at > text.length) return null
        var start = at
        while (start > 0 && isNameChar(text[start - 1])) start--
        var end = at
        while (end < text.length && isNameChar(text[end])) end++
        val name = text.substring(start, end).trimStart('\\').let { if (text.getOrNull(start) == '\\') "\\$it" else it }
        return name.takeIf { it.isNotEmpty() && it.trimStart('\\').first().let { c -> c.isLetter() || c == '_' } }
    }

    /** The classes, interfaces and traits a name written in context resolves to; empty for keywords and type parameters. */
    fun resolve(name: String, context: PsiElement): List<PhpClass> {
        if (name.lowercase() in NOT_A_CLASS) return emptyList()
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

    /** Whether the short name of an import appears in an erased type anywhere in the file. */
    fun mentions(file: PsiFile, shortName: String): Boolean =
        CachedValuesManager.getCachedValue(file) {
            val words = HashSet<String>()
            for (leaf in PsiTreeUtil.collectElementsOfType(file, PsiWhiteSpace::class.java)) {
                val text = leaf.text
                if (text.isBlank()) continue
                var i = 0
                while (i < text.length) {
                    if (!isNameChar(text[i]) || text[i] == '\\') { i++; continue }
                    var j = i
                    while (j < text.length && isNameChar(text[j]) && text[j] != '\\') j++
                    words.add(text.substring(i, j).lowercase())
                    i = j
                }
            }
            CachedValueProvider.Result.create(words, file)
        }.contains(shortName.lowercase())

    /** Lower-cased short name → FQN of every class import of the file. */
    private fun imports(file: PsiFile): Map<String, String> =
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

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '\\'
}
