package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.psi.tree.TokenSet

/**
 * PhpStorm's PHP parser definition with [PhaenonTokenTypes.ERASED] among the comment tokens, so
 * that the `PsiBuilder` built from it skips erased constructs the way it skips comments and makes
 * comment leaves of them.
 *
 * The token-set methods are delegated by hand: they are Java default methods, which Kotlin's
 * class delegation does not forward, and the defaults name the platform's whitespace, not PHP's.
 */
class PhaenonParserDefinition(private val php: ParserDefinition) : ParserDefinition by php {

    private val comments: TokenSet = TokenSet.orSet(php.commentTokens, TokenSet.create(PhaenonTokenTypes.ERASED))

    override fun getCommentTokens(): TokenSet = comments

    override fun getWhitespaceTokens(): TokenSet = php.whitespaceTokens

    override fun getStringLiteralElements(): TokenSet = php.stringLiteralElements

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements =
        php.spaceExistenceTypeBetweenTokens(left, right)
}
