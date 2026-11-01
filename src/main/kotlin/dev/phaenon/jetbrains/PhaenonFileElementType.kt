package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.psi.tree.IFileElementType
import com.jetbrains.php.lang.PhpLanguage

/**
 * The root AST node of a Phaenon file.
 *
 * It is a PHP file element type in every respect but one: it feeds the PHP parser from
 * [PhaenonLexer], which starts in scripting mode and erases Phaenon's additions to PHP from the
 * token stream. Underneath sits PhpStorm's own `PhpWithOptionalOpenTagLexer` (the one it uses for
 * PHP injected into strings): it peeks at the first token and only stays in HTML mode when the
 * file really begins with an open tag, which is exactly the Phaenon contract: PHP code from byte 0.
 *
 * The parse itself mirrors what PhpStorm does for its code fragments: build a `PsiBuilder` with
 * an explicit lexer and hand it to the stock PHP parser. The builder takes its comment tokens from
 * [PhaenonParserDefinition], so the erased constructs are skipped as comments, and is wrapped in
 * [PhaenonPsiBuilder] so that no block is parsed lazily (a lazy block would be re-lexed later
 * without the erasures). Everything below the root is therefore ordinary PHP PSI, produced by
 * PhpStorm, and the stub builder, indices, inspections and refactorings never learn that the file
 * was special.
 */
object PhaenonFileElementType : IFileElementType("phaenon.FILE", PhpLanguage.INSTANCE) {

    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val project = chameleon.psi.project
        val php = LanguageParserDefinitions.INSTANCE.forLanguage(PhpLanguage.INSTANCE)
        val builder = PsiBuilderImpl(project, PhaenonParserDefinition(php), PhaenonLexer(), chameleon, chameleon.chars)

        return php.createParser(project).parse(this, PhaenonPsiBuilder(project, builder)).firstChildNode
    }
}
