package dev.phaenon.jetbrains

import com.intellij.lang.PsiBuilder
import com.intellij.lang.SyntaxTreeBuilder
import com.intellij.lang.WhitespacesAndCommentsBinder
import com.intellij.lang.impl.PsiBuilderAdapter
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IElementType
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.parser.PhpPsiBuilder
import com.jetbrains.php.lang.parser.parsing.Namespace
import com.jetbrains.php.lang.parser.parsing.Statement
import com.jetbrains.php.lang.parser.parsing.classes.ClassDeclaration
import com.jetbrains.php.lang.parser.parsing.functions.Function

/**
 * The `PsiBuilder` the PHP parser runs on for a Phaenon file: PhpStorm's builder with one
 * behaviour changed — no block is parsed lazily.
 *
 * PhpStorm skips over every `{ … }` block of a function, method, closure or `try` while parsing a
 * file and collapses it into a lazy node, which is expanded later by re-lexing the block's text
 * with a plain PHP lexer. For PHP that is an optimisation; for a Phaenon file it would throw away
 * [PhaenonLexer]'s erasures inside every body, so `$x is Foo` or `new Box<int>()` would come back
 * as syntax errors as soon as the body is looked at. Intercepting the collapse and parsing the
 * block there and then, with the same statement parser PhpStorm uses when it expands a lazy block,
 * keeps the whole file on one lexer. The block becomes PhpStorm's own non-lazy group statement,
 * the node type it uses for the top level of a file and for code fragments, which every feature
 * handles alongside the lazy one (`PhpElementTypes.ANY_GROUP_STATEMENT`).
 *
 * The price is that an edit inside a body reparses the file rather than the block, which is how
 * PhpStorm worked before lazy blocks existed.
 *
 * An expression body, `=> expr;` (design/functions.md §3), reaches the parser as the block it
 * compiles to: [PhaenonLexer] hands over the arrow as `{` and `return`. The source has no `}` for
 * that block; it is closed here after its one statement, as the compiler's tree holds it.
 */
class PhaenonPsiBuilder(private val project: Project, delegate: PsiBuilder) : PsiBuilderAdapter(delegate) {

    private val php: PhpPsiBuilder by lazy { PhpPsiBuilder(project, this) }

    override fun mark(): PsiBuilder.Marker = Marker(super.mark())

    /** Parses the `{ … }` block at the current token into a non-lazy group statement. */
    private fun parseBlockNow() {
        val block = mark()
        if (tokenText == ARROW_BRACE) {
            // `=> expr;`: the `return expr;` (or `expr;` under void and never) the arrow stands for.
            php.match(PhpTokenTypes.chLBRACE)
            Statement.parse(php)
            block.done(PhpElementTypes.NON_LAZY_GROUP_STATEMENT)
            return
        }
        php.match(PhpTokenTypes.chLBRACE)
        while (!php.eof() && !php.compare(PhpTokenTypes.chRBRACE)) {
            val before = php.currentOffset
            // The same order PhpStorm uses when it expands a lazy block (StatementList.parseBlockDeep).
            var parsed = Function.parse(php, false)
            if (parsed === PhpElementTypes.EMPTY_INPUT) parsed = Namespace.parseDeclaration(php)
            if (parsed === PhpElementTypes.EMPTY_INPUT) parsed = Namespace.parseUse(php)
            if (parsed === PhpElementTypes.EMPTY_INPUT) parsed = ClassDeclaration.parse(php)
            if (parsed === PhpElementTypes.EMPTY_INPUT && !php.eof()) parsed = Statement.parse(php)
            if (parsed === PhpElementTypes.EMPTY_INPUT || before == php.currentOffset) {
                val unexpected = php.tokenType ?: break
                // PhpStorm's wording for a token no statement takes (its message helper is internal API).
                php.error("Unexpected: $unexpected")
                php.advanceLexer()
            }
        }
        php.match(PhpTokenTypes.chRBRACE)
        block.done(PhpElementTypes.NON_LAZY_GROUP_STATEMENT)
    }

    private companion object {
        /** The text of the `{` an expression body's arrow begins with: its first character. */
        const val ARROW_BRACE = "="
    }

    private inner class Marker(val delegate: PsiBuilder.Marker) : PsiBuilder.Marker {

        /** The builder; a marker has a `tokenType` of its own, so the name is kept explicit. */
        private val builder: PsiBuilder get() = this@PhaenonPsiBuilder

        override fun collapse(type: IElementType) {
            if (type === PhpElementTypes.GROUP_STATEMENT) {
                // PhpStorm has skipped the block (StatementList.parseFast); go back and parse it.
                delegate.rollbackTo()
                if (builder.tokenType === PhpTokenTypes.chLBRACE) {
                    parseBlockNow()
                } else {
                    skipBlockAsPhpStormDoes()
                }
                return
            }
            delegate.collapse(type)
        }

        /** The fallback for a block that does not start with `{`; mirrors StatementList.parseFast. */
        private fun skipBlockAsPhpStormDoes() {
            val marker = super@PhaenonPsiBuilder.mark()
            var balance = 0
            while (true) {
                val t = builder.tokenType ?: break
                if (t === PhpTokenTypes.chLBRACE || t === PhpTokenTypes.DOLLAR_LBRACE) {
                    balance++
                } else if (t === PhpTokenTypes.chRBRACE) {
                    balance--
                    if (balance <= 0) break
                }
                builder.advanceLexer()
            }
            if (builder.tokenType === PhpTokenTypes.chRBRACE) builder.advanceLexer()
            marker.collapse(PhpElementTypes.GROUP_STATEMENT)
        }

        override fun precede(): PsiBuilder.Marker = Marker(delegate.precede())
        override fun drop() = delegate.drop()
        override fun rollbackTo() = delegate.rollbackTo()
        override fun done(type: IElementType) = delegate.done(type)
        override fun error(message: String) = delegate.error(message)

        override fun doneBefore(type: IElementType, before: PsiBuilder.Marker) = delegate.doneBefore(type, unwrap(before))
        override fun doneBefore(type: IElementType, before: PsiBuilder.Marker, errorMessage: String) =
            delegate.doneBefore(type, unwrap(before), errorMessage)
        override fun errorBefore(message: String, before: PsiBuilder.Marker) = delegate.errorBefore(message, unwrap(before))

        override fun doneBefore(type: IElementType, before: SyntaxTreeBuilder.Marker) = delegate.doneBefore(type, unwrap(before))
        override fun doneBefore(type: IElementType, before: SyntaxTreeBuilder.Marker, errorMessage: String) =
            delegate.doneBefore(type, unwrap(before), errorMessage)
        override fun errorBefore(message: String, before: SyntaxTreeBuilder.Marker) = delegate.errorBefore(message, unwrap(before))

        override fun setCustomEdgeTokenBinders(left: WhitespacesAndCommentsBinder?, right: WhitespacesAndCommentsBinder?) =
            delegate.setCustomEdgeTokenBinders(left, right)

        override fun getTokenType(): IElementType? = delegate.tokenType
        override fun getStartOffset(): Int = delegate.startOffset
        override fun getEndOffset(): Int = delegate.endOffset
        override fun getStartIndex(): Int = delegate.startIndex
        override fun getEndIndex(): Int = delegate.endIndex
        override fun getErrorMessage(): String? = delegate.errorMessage
        override fun isCollapsed(): Boolean = delegate.isCollapsed

        private fun unwrap(marker: SyntaxTreeBuilder.Marker): PsiBuilder.Marker =
            if (marker is Marker) marker.delegate else marker as PsiBuilder.Marker
    }
}
