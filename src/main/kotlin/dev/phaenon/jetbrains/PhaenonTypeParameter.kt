package dev.phaenon.jetbrains

import com.intellij.openapi.util.TextRange
import com.intellij.pom.PsiDeclaredTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * The declaration of a type parameter: the name `T` inside the erased list `<T of AggregateId>`
 * that follows a class's or function's name. The list is one comment-like leaf to PhpStorm, so
 * the declaration has no element of its own; this one stands in for it as a navigation target,
 * placed at the name's offset so that "go to declaration" on a use of `T` lands on it. As a
 * declared target it is what [PhaenonTypeParameterDeclarations] reports at the name.
 */
class PhaenonTypeParameter(private val list: PsiElement, private val offsetInList: Int, private val name: String) : FakePsiElement(), PsiDeclaredTarget {

    override fun getParent(): PsiElement = list

    override fun getContainingFile(): PsiFile = list.containingFile

    override fun getName(): String = name

    override fun getText(): String = name

    override fun getTextRange(): TextRange = TextRange.from(textOffset, name.length)

    override fun getTextOffset(): Int = list.textRange.startOffset + offsetInList

    override fun getNavigationElement(): PsiElement = this

    /** The name, relative to this element: all of it. */
    override fun getNameIdentifierRange(): TextRange = TextRange(0, name.length)

    override fun isPhysical(): Boolean = true

    override fun isValid(): Boolean = list.isValid

    override fun isEquivalentTo(another: PsiElement?): Boolean = this == another

    override fun equals(other: Any?): Boolean =
        other is PhaenonTypeParameter && other.list == list && other.offsetInList == offsetInList

    override fun hashCode(): Int = 31 * list.hashCode() + offsetInList
}
