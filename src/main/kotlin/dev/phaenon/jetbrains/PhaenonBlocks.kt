package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpExpression
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * The closures [PhaenonLexer] makes of a `concurrently` block used as a value and of `spawn`
 * (design/concurrency.md §4, §6): `concurrently {` handed to the parser as `function () {`,
 * `spawn {` as `function () {`, `spawn expr` as `fn () => expr` — the closures they compile to.
 *
 * To PhpStorm such a closure has a scope of its own and no `use` clause; to Phaenon its body
 * sees the enclosing function's variables (the compiler writes the `use` clause, by reference
 * for what the body assigns and the function reads after it). [scopeOf] is the function a
 * variable really belongs to, through any number of blocks, for the findings that ask.
 */
object PhaenonBlocks {

    enum class Kind(val keyword: String) { CONCURRENTLY("concurrently"), SPAWN("spawn") }

    /** What [function] is the closure of, or null when it is an ordinary function or closure. */
    @JvmStatic
    fun kind(function: Function?): Kind? {
        if (function == null || !function.isClosure || !PhaenonErasedTypes.isPhaenon(function)) return null
        val text = function.node.chars
        return Kind.entries.firstOrNull { text.startsWith(it.keyword) && !text.startsWith(it.keyword + "_") }
    }

    /** Whether [offset] falls on the keyword of a block or a spawn, where PhpStorm reports what it finds about the closure. */
    @JvmStatic
    fun isKeywordAt(file: PsiFile, offset: Int): Boolean {
        val function = PsiTreeUtil.getParentOfType(file.findElementAt(offset), Function::class.java, false) ?: return false
        val kind = kind(function) ?: return false
        return offset < function.textRange.startOffset + kind.keyword.length
    }

    /** The innermost function around [element] that is not a block: whose variables the element's are. Null at file level. */
    @JvmStatic
    fun scopeOf(element: PsiElement): Function? {
        var function = PsiTreeUtil.getParentOfType(element, Function::class.java)
        while (function != null && kind(function) != null) function = PsiTreeUtil.getParentOfType(function, Function::class.java)
        return function
    }

    /** The innermost function around [element], block or not. */
    private fun innermost(element: PsiElement): Function? = PsiTreeUtil.getParentOfType(element, Function::class.java)

    /**
     * Whether `$name` at [variable] is one the enclosing function knows across a block boundary: a
     * variable read in a block that the function — or a block it holds — defines earlier, or a variable
     * read after a block that assigns it. PhpStorm, which sees each block as a closure without a
     * `use` clause, calls such a variable undefined.
     */
    @JvmStatic
    fun isKnownAcrossBlocks(variable: Variable): Boolean {
        val name = variable.name ?: return false
        val scope = scopeOf(variable)
        val here = innermost(variable)
        val insideBlock = kind(here) != null
        val offset = variable.textRange.startOffset
        if (insideBlock && scope != null && scope.parameters.any { it.name == name }) return true
        return occurrences(scope ?: variable.containingFile, name).any { other ->
            if (other.textRange.startOffset >= offset) return@any false
            val there = innermost(other)
            if (scopeOf(other) != scope) return@any false
            if (insideBlock) true else kind(there) != null
        }
    }

    /**
     * Whether the variable or parameter `$name` declared at [declaration] is also used across a block
     * boundary in its function: read in a block when declared outside, or read outside when assigned
     * in a block. PhpStorm's unused-variable and unused-parameter inspections see only one side.
     */
    @JvmStatic
    fun isUsedAcrossBlocks(declaration: PsiElement, name: String): Boolean {
        val scope = if (declaration is Parameter) PsiTreeUtil.getParentOfType(declaration, Function::class.java) else scopeOf(declaration)
        val here = if (declaration is Parameter) scope else innermost(declaration)
        return occurrences(scope ?: declaration.containingFile, name).any { other ->
            other !== declaration && scopeOf(other) == scope && innermost(other) != here
        }
    }

    /** A block or spawn assigned to a variable: the closure, its kind, and whether it went into an element (`$tasks[$k] = spawn …`). */
    data class Assigned(val function: Function, val kind: Kind, val element: Boolean)

    /**
     * The blocks and spawns assigned in [scope] — a function, or a file at its top level — by
     * variable name, block bodies included and ordinary closures left out (they have scopes of their
     * own). Cached with the PSI.
     */
    @JvmStatic
    fun assignedValues(scope: PsiElement): Map<String, List<Assigned>> {
        val file = scope.containingFile ?: return emptyMap()
        val text = file.viewProvider.contents
        if (!text.contains("concurrently") && !text.contains("spawn")) return emptyMap()
        return CachedValuesManager.getCachedValue(scope) {
            val result = HashMap<String, MutableList<Assigned>>()
            for (assignment in PsiTreeUtil.findChildrenOfType(scope, AssignmentExpression::class.java)) {
                val value = assignment.value as? PhpExpression ?: continue
                val function = value.firstChild as? Function ?: continue
                val kind = kind(function) ?: continue
                var target = assignment.variable
                var element = false
                while (target is ArrayAccessExpression) {
                    element = true
                    target = target.value
                }
                val variable = target as? Variable ?: continue
                val name = variable.name ?: continue
                if (scopeOf(variable) != (scope as? Function)) continue
                result.getOrPut(name) { ArrayList() }.add(Assigned(function, kind, element))
            }
            CachedValueProvider.Result.create(result as Map<String, List<Assigned>>, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    private fun occurrences(scope: PsiElement, name: String): List<Variable> =
        PsiTreeUtil.findChildrenOfType(scope, Variable::class.java).filter { it.name == name }
}
