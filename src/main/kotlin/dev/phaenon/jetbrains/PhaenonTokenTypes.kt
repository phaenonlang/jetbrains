package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILeafElementType
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.PhpLanguage

/** The one token type the plugin adds to PHP's. */
object PhaenonTokenTypes {

    /**
     * An erased Phaenon construct: a type PHP cannot spell (`list<User>`, `<T of object>`,
     * `class<T>`, `(A, B)`), an `is` / `as` test, a `type` alias. To PhpStorm's parser it is a
     * comment, skipped like whitespace ([PhaenonParserDefinition]), so the tree below is ordinary
     * PHP PSI; in that tree it is a [PhaenonErasedLeaf] at the construct's own offsets, whose
     * text the type provider reads back and whose class names
     * [PhaenonErasedReferenceContributor] turns into references.
     *
     * The type creates its own leaf: the platform's AST factory would make a comment of it only
     * if PHP's own parser definition listed it, and a plain leaf otherwise.
     */
    @JvmField
    val ERASED: IElementType = object : IElementType("PHAENON_ERASED", PhpLanguage.INSTANCE), ILeafElementType {
        override fun createLeafNode(leafText: CharSequence): ASTNode = PhaenonErasedLeaf(leafText)
    }

    @JvmStatic
    fun isErased(element: PsiElement?): Boolean = element != null && PsiUtilCore.getElementType(element) === ERASED
}

/** The comment leaf an erased construct becomes; its references come from the contributors. */
class PhaenonErasedLeaf(text: CharSequence) : PsiCommentImpl(PhaenonTokenTypes.ERASED, text) {

    override fun getReferences(): Array<PsiReference> = ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun toString(): String = "PhaenonErased('${text}')"
}
