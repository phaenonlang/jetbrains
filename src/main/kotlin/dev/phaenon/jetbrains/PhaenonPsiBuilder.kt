package dev.phaenon.jetbrains

import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lang.SyntaxTreeBuilder
import com.intellij.lang.WhitespacesAndCommentsBinder
import com.intellij.lang.impl.PsiBuilderAdapter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.tree.IElementType
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `PsiBuilder` the PHP parser runs on for a Phaenon file: PhpStorm's builder with one
 * behaviour changed — no block is parsed lazily.
 *
 * PhpStorm skips over every `{ … }` block of a function, method, closure or `try` while parsing a
 * file and collapses it into a lazy node, which is expanded later by re-lexing the block's text
 * with a plain PHP lexer. For PHP that is an optimisation; for a Phaenon file it would throw away
 * [PhaenonLexer]'s erasures inside every body, so `new Box<int>()` or `first<User>($rows)` would
 * come back as syntax errors as soon as the body is looked at. Intercepting the collapse and
 * parsing the block there and then keeps the whole file on one lexer. The block becomes PhpStorm's
 * own non-lazy group statement, the node type it uses for the top level of a file and for code
 * fragments, which every feature handles alongside the lazy one
 * (`PhpElementTypes.ANY_GROUP_STATEMENT`).
 *
 * **How a body is parsed.** PhpStorm's statement parser is internal API, its parser is not: it is
 * the `PsiParser` of the PHP parser definition, the one that parses the file, and it reads a
 * statement as PhpStorm's expansion of a lazy block does. So the body is parsed by running that
 * parser again, on this builder, between the body's braces. While it runs the builder has a
 * *fence* at the body's closing brace and answers there as it does at the end of the input —
 * [eof] is true, there is no token, [advanceLexer] does nothing, the text ends — so the parser
 * reads the body's statements and stops. The two markers it opens around them, the root it was
 * asked for and its statement list, are dropped when it closes them as those nodes, which leaves
 * the statements as the children of the body; the tree it asks for at the end is not built yet,
 * and it gets a node nobody looks at. A body inside the body is collapsed in its turn, with a fence of its own
 * inside the first.
 *
 * The parser's loop over the statements (`Program.parse`) is not the expansion's
 * (`StatementList.parseBlockDeep`), and the two part on code that is no PHP. A `?>` ends the code
 * for the parser where the expansion reads on: at the start of the input, which [closingTagFirst]
 * makes up for, and after a token no statement took, where the rest of the body stays unparsed. A
 * `}` between statements is such a token to the parser, while the expansion takes the first one
 * for the block's end and goes on. And a parse PhpStorm gives up (`PhpParserException`) fails the
 * file here, where the expansion leaves the body empty. `PhaenonTreeEqualityTest` names the trees
 * that differ for it.
 *
 * None of that is a contract of PhpStorm's. It rests on how its parser drives a builder: that a
 * body is collapsed into `GROUP_STATEMENT` on a marker that starts at its `{`, that `parse` opens
 * those two markers and no other before the statements, and that it stops at the end of the input
 * without a token of its own. Were one of them to change, a body's tree would come out wrong;
 * `PhaenonTreeEqualityTest` is what says so, by comparing the trees with PhpStorm's own, and
 * [parseBody] reports markers that are not the two it knows.
 *
 * The price of bodies that are not lazy is that an edit inside a body reparses the file rather
 * than the block, which is how PhpStorm worked before lazy blocks existed.
 *
 * An expression body, `=> expr;` (design/functions.md §3), reaches the parser as the block it
 * compiles to: [PhaenonLexer] hands over the arrow as `{` and `return`. The source has no `}` for
 * that block; its fence stands behind the `;` that ends the expression, where the lexer ends the
 * body's scope as well ([bodyEnd]), and the block is closed there, as the compiler's tree holds it.
 *
 * The empty token [PhaenonLexer] hands over before a method declared without a keyword,
 * [PhaenonTokenTypes.FUNCTION], is `function` to the parser: the builder answers its type as
 * PHP's keyword, and the leaf the platform builds of it is one ([PhaenonTokenTypes.FUNCTION]).
 */
class PhaenonPsiBuilder(private val parser: PsiParser, delegate: PsiBuilder) : PsiBuilderAdapter(delegate) {

    /** Where the body being parsed ends: a token's index among all of the lexer's, and its offset. */
    private class End(val index: Int, val offset: Int)

    /** The end of the innermost body being parsed, where the input ends for the parser; null outside a body, and in one that nothing ends. */
    private var fence: End? = null

    /** How many bodies are being parsed, one inside the other. */
    private var bodies = 0

    /** How many of the markers handed out next are the parser's own around a body's statements. */
    private var wrappersToCome = 0

    /** How many of those markers the parser has not closed yet. */
    private var wrappersOpen = 0

    private val treeNotBuiltYet: ASTNode by lazy { ASTFactory.whitespace("") }

    override fun mark(): PsiBuilder.Marker {
        if (wrappersToCome == 0) return Marker(super.mark(), wrapperOf = null)
        val type = PARSER_WRAPPERS[PARSER_WRAPPERS.size - wrappersToCome]
        wrappersToCome--
        wrappersOpen++
        return Marker(super.mark(), wrapperOf = type)
    }

    override fun getTokenType(): IElementType? = if (atFence()) null else asParserSees(super.getTokenType())

    override fun getTokenText(): String? = if (atFence()) null else super.getTokenText()

    override fun eof(): Boolean = atFence() || super.eof()

    override fun advanceLexer() {
        if (!atFence()) super.advanceLexer()
    }

    /** No further than the fence. PhpStorm's parser does not advance by raw tokens; if it comes to, it stays in the body. */
    override fun rawAdvanceLexer(steps: Int) {
        val end = fence ?: return super.rawAdvanceLexer(steps)
        val left = end.index - rawTokenIndex()
        if (left > 0) super.rawAdvanceLexer(minOf(steps, left))
    }

    override fun lookAhead(steps: Int): IElementType? {
        if (fence == null) return asParserSees(super.lookAhead(steps))
        // What the parser would find after as many steps, none of which passes the fence.
        val here = super.mark()
        repeat(steps) { advanceLexer() }
        val type = tokenType
        here.rollbackTo()
        return type
    }

    override fun rawLookup(steps: Int): IElementType? = if (behindFence(steps)) null else asParserSees(super.rawLookup(steps))

    override fun rawTokenTypeStart(steps: Int): Int = if (behindFence(steps)) fence!!.offset else super.rawTokenTypeStart(steps)

    override fun getOriginalText(): CharSequence = super.getOriginalText().let { text -> fence?.let { text.subSequence(0, it.offset) } ?: text }

    /** The parser of a body asks for the tree when it is done ([parseBody]); the file's is built once, for the file's parser. */
    override fun getTreeBuilt(): ASTNode = if (bodies > 0) treeNotBuiltYet else super.getTreeBuilt()

    private fun asParserSees(type: IElementType?): IElementType? = if (type === PhaenonTokenTypes.FUNCTION) PhpTokenTypes.kwFUNCTION else type

    /** Whether the current token is the fence or stands behind it: the end of the input, to the parser of a body. */
    private fun atFence(): Boolean {
        val end = fence ?: return false
        // Past whitespace and comments first, as every look at the current token goes.
        super.getTokenType()
        return rawTokenIndex() >= end.index
    }

    /** The same of the token [steps] of the lexer's tokens away, whitespace and comments counted. */
    private fun behindFence(steps: Int): Boolean = fence.let { it != null && rawTokenIndex() + steps >= it.index }

    /**
     * Parses the block at the current token — a `{`, or the `{` an expression body's arrow is —
     * into a non-lazy group statement, with PhpStorm's parser between the block's ends.
     */
    private fun parseBody() {
        val arrow = tokenText == ARROW_BRACE
        val end = bodyEnd(arrow)
        val body = Marker(super.mark(), wrapperOf = null)
        advanceLexer()
        closingTagFirst()

        val outerFence = fence
        val outerToCome = wrappersToCome
        val open = wrappersOpen
        // Nothing ends the body: it runs to the end of the body around it, or of the file.
        if (end != null) fence = end
        wrappersToCome = PARSER_WRAPPERS.size
        bodies++
        try {
            parser.parse(BODY, this)
            if (wrappersToCome != 0 || wrappersOpen != open) {
                reportParserChange("of the ${PARSER_WRAPPERS.size} markers around a body's statements it opened ${PARSER_WRAPPERS.size - wrappersToCome} and left ${wrappersOpen - open} open")
            }
        } finally {
            bodies--
            wrappersOpen = open
            wrappersToCome = outerToCome
            fence = outerFence
        }

        if (!arrow) {
            // PhpStorm's wording for a body without its brace (its message helper is internal API).
            if (tokenType === PhpTokenTypes.chRBRACE) advanceLexer() else error("Expected: ${PhpTokenTypes.chRBRACE}")
        }
        body.done(PhpElementTypes.NON_LAZY_GROUP_STATEMENT)
    }

    /**
     * A body that begins with `?>`: the tag and the text behind it, as PhpStorm's statement parser
     * reads them, here because its parser would not. To PhpStorm's parser a closing tag at the start
     * of its input ends the code, and it would leave the body's tokens unparsed; after a statement it
     * reads the tag itself. Neither tag belongs in Phaenon source ([PhaenonPhpTagAnnotator]); a body
     * that has them keeps its tree.
     */
    private fun closingTagFirst() {
        if (tokenType !== PhpTokenTypes.PHP_CLOSING_TAG) return
        advanceLexer()
        if (tokenType !== PhpTokenTypes.HTML) return
        val html = Marker(super.mark(), wrapperOf = null)
        advanceLexer()
        html.done(PhpElementTypes.HTML)
    }

    /**
     * Where the body that opens at the current token ends, or null when nothing ends it before the
     * body around it does, or the file.
     *
     * A block ends at its closing brace, found as PhpStorm finds it when it skips a block
     * (`StatementList.parseFast`), by counting `{`, `${` and `}` — but for the `{` of an
     * expression body's arrow, which no `}` answers. An expression body has no brace to end at. It
     * ends behind the first `;` outside any braces of its own — a closure's, a `match`'s, an
     * anonymous class's, an interpolation's — which is where its one statement ends and where
     * [PhaenonLexer] ends the body's scope; on unfinished code, as the lexer does, at the `}` that
     * closes what the body stands in.
     */
    private fun bodyEnd(arrow: Boolean): End? {
        val start = super.mark()
        super.advanceLexer()
        var balance = 1
        var end: End? = null
        while (true) {
            val type = tokenType ?: break
            if (type === PhpTokenTypes.DOLLAR_LBRACE || type === PhpTokenTypes.chLBRACE && tokenText != ARROW_BRACE) {
                balance++
            } else if (type === PhpTokenTypes.chRBRACE) {
                balance--
                if (balance <= 0) {
                    end = End(rawTokenIndex(), super.rawTokenTypeStart(0))
                    break
                }
            } else if (arrow && balance == 1 && type === PhpTokenTypes.opSEMICOLON) {
                super.advanceLexer()
                end = End(rawTokenIndex(), super.rawTokenTypeStart(0))
                break
            }
            super.advanceLexer()
        }
        start.rollbackTo()
        return end
    }

    /** Once: what follows it is the same finding in every body of every file. */
    private fun reportParserChange(what: String) {
        if (PARSER_CHANGE_REPORTED.getAndSet(true)) return
        LOG.error("PhpStorm's parser no longer drives the builder as PhaenonPsiBuilder expects: $what. The trees of .phn files may be wrong; PhaenonTreeEqualityTest tells.")
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(PhaenonPsiBuilder::class.java)

        /** The text of the `{` an expression body's arrow begins with: its first character. */
        const val ARROW_BRACE = "="

        /** The root PhpStorm's parser is asked for when it parses a body: a node that is never built. */
        val BODY: IElementType = IElementType("PHAENON_BODY", PhpLanguage.INSTANCE)

        /**
         * The nodes of the markers PhpStorm's parser opens around the statements it parses, before
         * the first of them and in this order: the root `PsiParser.parse` is asked for, and its
         * statement list.
         */
        val PARSER_WRAPPERS: List<IElementType> = listOf(BODY, PhpElementTypes.NON_LAZY_GROUP_STATEMENT)

        val PARSER_CHANGE_REPORTED = AtomicBoolean()
    }

    /** A marker of the builder's; [wrapperOf] is the node of one the parser opens around a body's statements, null on any other. */
    private inner class Marker(val delegate: PsiBuilder.Marker, private val wrapperOf: IElementType?) : PsiBuilder.Marker {

        /** The builder; a marker has a `tokenType` of its own, so the name is kept explicit. */
        private val builder: PsiBuilder get() = this@PhaenonPsiBuilder

        override fun collapse(type: IElementType) {
            if (type === PhpElementTypes.GROUP_STATEMENT) {
                // PhpStorm has skipped the block (StatementList.parseFast); go back and parse it.
                delegate.rollbackTo()
                if (builder.tokenType === PhpTokenTypes.chLBRACE) {
                    parseBody()
                } else {
                    skipBlockAsPhpStormDoes()
                }
                return
            }
            delegate.collapse(type)
        }

        /**
         * The fallback for a block that does not start with `{`; mirrors StatementList.parseFast.
         * PhpStorm's parser skips a block only at a `{`: should it come here, the block stays lazy,
         * loses its erasures when it is expanded, and that is said.
         */
        private fun skipBlockAsPhpStormDoes() {
            reportParserChange("it skipped a block that does not begin with a brace, which stays lazy")
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

        override fun done(type: IElementType) {
            if (wrapperOf == null) return delegate.done(type)
            wrappersOpen--
            if (type === wrapperOf) {
                // The parser's own node around a body's statements: the body is that node ([parseBody]).
                delegate.drop()
            } else {
                // Not the node this builder knows there: it stays in the tree, and the change is said.
                delegate.done(type)
                reportParserChange("a marker expected to become $wrapperOf became $type")
            }
        }

        override fun precede(): PsiBuilder.Marker = Marker(delegate.precede(), wrapperOf = null)
        override fun drop() = delegate.drop()
        override fun rollbackTo() = delegate.rollbackTo()
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
