package dev.phaenon.jetbrains

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer

/**
 * An erased construct is a comment leaf to the platform, which would spell-check its text as
 * prose. It is code — class names, keywords, type parameters — and PhpStorm does not spell-check
 * a class *reference* in PHP either, so none of it is checked.
 */
class PhaenonSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun isMyContext(element: PsiElement): Boolean = PhaenonTokenTypes.isErased(element)

    override fun getTokenizer(element: PsiElement): Tokenizer<*> = EMPTY_TOKENIZER
}
