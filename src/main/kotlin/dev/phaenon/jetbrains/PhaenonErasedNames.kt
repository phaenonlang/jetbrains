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
 * statements, then the current namespace, then the global one. A name no class bears may be a
 * `type` alias's, which [PhaenonAliases] finds.
 *
 * A `type` declaration is erased whole, so its text holds more than names of types: the keys of a
 * shape (`id` in `array{id: int}`), quoted keys, comments and the declaration's docblock. [names]
 * is the one reading of an erased text that tells them apart, and everything here goes by it.
 */
object PhaenonErasedNames {

    /** The words of a Phaenon construct that name no class. */
    private val NOT_A_CLASS: Set<String> = setOf(
        "list", "map", "class", "bytes", "of", "out", "in", "type",
        "int", "float", "string", "bool", "mixed", "null", "void", "never", "object", "array", "callable", "iterable",
        "self", "static", "parent", "true", "false", "throws", "nothrow",
    )

    private val NAME: Regex = Regex("""\\?[A-Za-z_][A-Za-z0-9_]*(\\[A-Za-z_][A-Za-z0-9_]*)*""")

    /**
     * Each class name in an erased leaf's text: the range of its last segment, and the name to
     * resolve — as written, except that a bare `Closure` before a signature is `\Closure`, as the
     * compiler reads it whatever the namespace and imports.
     */
    fun classNames(leaf: PsiElement): List<Pair<TextRange, String>> {
        val text = leaf.text
        val alias = PhaenonType.alias(text)
        val typeParameters = PhaenonErasedTypes.typeParametersInScope(leaf) + alias?.parameters.orEmpty().map { it.name }
        val names = ArrayList<Pair<TextRange, String>>()
        for ((at, name) in names(text)) {
            val plain = name.trimStart('\\')
            if (plain.lowercase() in NOT_A_CLASS || plain in typeParameters) continue
            if (alias != null && at <= alias.offset) continue
            val last = name.lastIndexOf('\\') + 1
            val range = TextRange(at + last, at + name.length)
            names.add(range to if (name == "Closure" && isClosureSignatureAt(text, range.endOffset)) "\\Closure" else name)
        }
        return names
    }

    /**
     * The names in an erased text, each with its offset: every word or qualified name that stands
     * where a type may. Left out are a shape's keys (a word right after the `{` or a `,` of a
     * shape, before `:` or `?:`), what a quoted key holds, and comments, a docblock among them.
     */
    fun names(text: String): List<Pair<Int, String>> {
        val names = ArrayList<Pair<Int, String>>()
        val open = ArrayList<Char>()
        var previous = ' '
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text.startsWith("//", i) || c == '#' -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it + 1 }
                c == '\'' || c == '"' -> {
                    i = text.indexOf(c, i + 1).let { if (it < 0) text.length else it + 1 }
                    previous = c
                }
                c.isLetter() || c == '_' || c == '\\' -> {
                    val match = NAME.matchAt(text, i)
                    if (match == null) {
                        i++
                    } else {
                        val key = open.lastOrNull() == '{' && (previous == '{' || previous == ',') && isKeyAt(text, match.range.last + 1)
                        if (!key) names.add(i to match.value)
                        i = match.range.last + 1
                    }
                    previous = 'a'
                }
                else -> {
                    when (c) {
                        '{', '<', '(' -> open.add(c)
                        '}', '>', ')' -> if (open.isNotEmpty()) open.removeAt(open.size - 1)
                    }
                    previous = c
                    i++
                }
            }
        }
        return names
    }

    /** Whether what follows a word at [at] makes it a shape's key: `:`, or `?` and `:`, blanks aside. */
    private fun isKeyAt(text: String, at: Int): Boolean {
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && text[i] == '?') {
            i++
            while (i < text.length && text[i].isWhitespace()) i++
        }
        return i < text.length && text[i] == ':'
    }

    /** Whether a closure signature `(…)` starts at [at], whitespace aside. */
    private fun isClosureSignatureAt(text: String, at: Int): Boolean {
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '('
    }

    /**
     * Each use of a type parameter in scope in an erased leaf's text: its range and name. In a
     * `type` declaration the parameters are its own, `T` in `type Page<T> = array{items: list<T>}`,
     * used wherever the name stands again after its declaration.
     */
    fun typeParameters(leaf: PsiElement): List<Pair<TextRange, String>> {
        val text = leaf.text
        val alias = PhaenonType.alias(text)
        if (alias != null) {
            val declared = alias.parameters.associate { it.name to it.offset }
            return names(text)
                .filter { (at, name) -> declared[name]?.let { at > it } == true }
                .map { (at, name) -> TextRange(at, at + name.length) to name }
        }
        val typeParameters = PhaenonErasedTypes.typeParametersInScope(leaf)
        if (typeParameters.isEmpty()) return emptyList()
        return names(text)
            .filter { it.second in typeParameters }
            .map { (at, name) -> TextRange(at, at + name.length) to name }
    }

    /** The classes, interfaces and traits a name written in context resolves to; empty for keywords and type parameters. */
    fun resolve(name: String, context: PsiElement): List<PhpClass> {
        if (name.trimStart('\\').lowercase() in NOT_A_CLASS) return emptyList()
        val index = PhpIndex.getInstance(context.project)
        if (name.startsWith("\\")) return index.getAnyByFQN(name).toList()
        val first = name.substringBefore('\\')
        val rest = name.substring(first.length)
        imports(context.containingFile)[first.lowercase()]?.let { return index.getAnyByFQN(it + rest).toList() }
        val namespace = namespaceOf(context)?.fqn?.trim('\\').orEmpty()
        val inNamespace = if (namespace.isEmpty()) "\\$name" else "\\$namespace\\$name"
        index.getAnyByFQN(inNamespace).takeIf { it.isNotEmpty() }?.let { return it.toList() }
        return index.getAnyByFQN("\\$name").toList()
    }

    /**
     * The qualified name a name written in context stands for where it is no class's: through the
     * file's imports, else in its namespace, and never the global one (generics.md §11.6), which is
     * where an alias is looked for. Null for keywords.
     */
    fun qualified(name: String, context: PsiElement): String? {
        if (name.trimStart('\\').lowercase() in NOT_A_CLASS) return null
        if (name.startsWith("\\")) return name
        val first = name.substringBefore('\\')
        val namespace = namespaceOf(context)?.fqn?.trim('\\').orEmpty()
        // `namespace\Row` is the current namespace's, whatever the imports.
        if (first.equals("namespace", ignoreCase = true) && first.length < name.length) return (if (namespace.isEmpty()) "" else "\\$namespace") + name.substring(first.length)
        imports(context.containingFile)[first.lowercase()]?.let { return it + name.substring(first.length) }
        return if (namespace.isEmpty()) "\\$name" else "\\$namespace\\$name"
    }

    /**
     * The namespace [element] stands in: the one around it, or, for what the parser leaves behind
     * a file's last statement, the last one opened before it. An erased `type` declaration is a
     * comment to the parser, and comments at the end of `namespace App; type Row = …;` are the
     * file's, outside the namespace's element.
     */
    fun namespaceOf(element: PsiElement): PhpNamespace? {
        PsiTreeUtil.getParentOfType(element, PhpNamespace::class.java)?.let { return it }
        val file = element.containingFile ?: return null
        val at = element.textRange.startOffset
        return PsiTreeUtil.findChildrenOfType(file, PhpNamespace::class.java).lastOrNull { it.textRange.startOffset < at }
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
        val namespace = namespaceOf(context)?.fqn?.trim('\\').orEmpty()
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
                for ((_, name) in names(leaf.text)) words.add(name.trimStart('\\').substringBefore('\\').lowercase())
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
