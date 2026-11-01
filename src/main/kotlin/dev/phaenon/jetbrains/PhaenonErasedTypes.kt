package dev.phaenon.jetbrains

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration

/**
 * Reads Phaenon types back out of a `.phn` file's PSI.
 *
 * [PhaenonLexer] erases the types PHP cannot spell into [PhaenonTokenTypes.ERASED] tokens, so in
 * the tree they are comment leaves of that type, sitting where the type was: before a parameter
 * or property name, between a parameter list and a function body. These functions collect that
 * text; [PhaenonTypeText] turns it into a `PhpType`.
 */
object PhaenonErasedTypes {

    fun isPhaenon(element: PsiElement?): Boolean =
        element != null && PhaenonFiles.isPhaenonFile(element.containingFile?.viewProvider?.virtualFile)

    /**
     * The erased type text in front of a parameter's or property's name, `""` when the
     * declaration had no Phaenon-only type. Native members of a union (`Foo|list<int>`) stay in
     * the PSI as a type declaration and are skipped here: PhpStorm types them itself.
     */
    fun beforeName(declaration: PhpNamedElement): String {
        if (declaration !is Parameter && declaration !is Field) return ""
        val name = declaration.nameIdentifier ?: return ""
        val parts = ArrayList<String>()
        var leaf: PsiElement? = PsiTreeUtil.prevLeaf(name, true)
        while (leaf != null) {
            val type = PsiUtilCore.getElementType(leaf)
            when {
                PhaenonTokenTypes.isErased(leaf) -> parts.add(leaf.text.trim())
                leaf is PsiWhiteSpace || leaf is PsiComment -> Unit
                type === PhpTokenTypes.opBIT_AND || type === PhpTokenTypes.opVARIADIC -> Unit
                PsiTreeUtil.getParentOfType(leaf, PhpTypeDeclaration::class.java) != null -> Unit
                else -> break
            }
            leaf = PsiTreeUtil.prevLeaf(leaf, true)
        }
        parts.reverse()
        return join(parts)
    }

    /** The erased return type text of a function, method or closure, `""` when there was none. */
    fun returnType(function: Function): String {
        // The body, the `;` of an abstract method, or the expression of an arrow function.
        val tail = function.lastChild ?: return ""
        if (tail.node.elementType === PhpTokenTypes.chRPAREN) return ""
        val parts = ArrayList<String>()
        var leaf: PsiElement? = PsiTreeUtil.prevLeaf(tail, true)
        while (leaf != null) {
            val type = PsiUtilCore.getElementType(leaf)
            when {
                PhaenonTokenTypes.isErased(leaf) -> parts.add(leaf.text.trim())
                leaf is PsiWhiteSpace || leaf is PsiComment -> Unit
                type === PhpTokenTypes.opCOLON || type === PhpTokenTypes.opHASH_ARRAY -> Unit
                PsiTreeUtil.getParentOfType(leaf, PhpTypeDeclaration::class.java) != null -> Unit
                else -> break
            }
            leaf = PsiTreeUtil.prevLeaf(leaf, true)
        }
        parts.reverse()
        return join(parts)
    }

    /**
     * The names of the type parameters in scope at [element]: those declared on the enclosing
     * functions and classes (`class Box<T>`, `function f<U>`, `fn<V>`), read from the erased
     * `<…>` list that follows the declaration's name.
     */
    fun typeParametersInScope(element: PsiElement): Set<String> {
        val names = HashSet<String>()
        var scope: PsiElement? = element
        while (scope != null) {
            declarationList(scope)?.let { names.addAll(PhaenonTypeText.parameterNames(it.text)) }
            scope = scope.parent
        }
        return names
    }

    /**
     * Where the type parameter [name], used at [element], is declared: the innermost enclosing
     * declaration list `<…>` that names it. Null when no enclosing declaration does.
     */
    fun typeParameterDeclaration(element: PsiElement, name: String): PhaenonTypeParameter? {
        var scope: PsiElement? = element
        while (scope != null) {
            val list = declarationList(scope)
            if (list != null) {
                val offset = PhaenonTypeText.parameters(list.text).firstOrNull { it.second == name }?.first
                if (offset != null) return PhaenonTypeParameter(list, offset, name)
            }
            scope = scope.parent
        }
        return null
    }

    /** Whether [leaf] is the erased `<…>` list declaring a function's or class's type parameters. */
    fun isDeclarationList(leaf: PsiElement): Boolean {
        val declaration = leaf.parent ?: return false
        return declarationList(declaration) === leaf
    }

    /** The erased leaf holding a function's or class's `<…>` type parameter list, if it declares one. */
    private fun declarationList(declaration: PsiElement): PsiElement? {
        if (declaration !is Function && declaration !is PhpClass) return null
        // `function f<T>(`, `class Box<T>`: the list follows the name; `fn<T>(`: it follows the keyword.
        val anchor = (declaration as? PhpNamedElement)?.nameIdentifier
            ?: declaration.firstChild?.takeIf { PsiUtilCore.getElementType(it) === PhpTokenTypes.kwFN }
            ?: return null
        var leaf: PsiElement? = PsiTreeUtil.nextLeaf(anchor, true)
        while (leaf is PsiWhiteSpace || leaf is PsiComment) {
            if (PhaenonTokenTypes.isErased(leaf) && leaf.text.trim().startsWith("<")) return leaf
            leaf = PsiTreeUtil.nextLeaf(leaf, true)
        }
        return null
    }

    /** The erased texts of one declaration are the Phaenon members of its union. */
    private fun join(parts: List<String>): String =
        parts.joinToString("|").trim('|', '&', ' ').replace(Regex("\\|\\s*\\|"), "|")
}
