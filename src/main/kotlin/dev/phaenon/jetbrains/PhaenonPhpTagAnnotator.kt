package dev.phaenon.jetbrains

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.lexer.PhpTokenTypes

/**
 * Phaenon source is PHP code from the first byte: there is no `<?php` and no `?>`. The lexer
 * tolerates an open tag (so a pasted PHP file still parses), but it must not go unnoticed, so
 * both tags are reported as errors in `.phn` files.
 */
class PhaenonPhpTagAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (!TAG_TOKENS.contains(PsiUtilCore.getElementType(element))) return
        if (!PhaenonFiles.isPhaenonFile(element.containingFile?.viewProvider?.virtualFile)) return

        holder.newAnnotation(HighlightSeverity.ERROR, "PHP open and close tags are not allowed in Phaenon source: a .phn file is PHP code from the first byte")
            .range(element)
            .create()
    }

    private companion object {
        val TAG_TOKENS: TokenSet = TokenSet.orSet(PhpTokenTypes.tsPHP_OPENING_TAGS, TokenSet.create(PhpTokenTypes.PHP_CLOSING_TAG))
    }
}
