package dev.phaenon.jetbrains

import com.intellij.formatting.Alignment
import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Block
import com.intellij.formatting.BlockEx
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.CustomFormattingModelBuilder
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.formatter.DocumentBasedFormattingModel
import com.intellij.psi.formatter.FormattingDocumentModelImpl
import com.intellij.psi.formatter.common.ExtraRangesProvider
import com.intellij.psi.formatter.xml.HtmlPolicy
import com.intellij.psi.templateLanguages.SimpleTemplateLanguageFormattingModelBuilder
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.PhpFormattingModelBuilder
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.GroupStatement
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * The formatter of `.phn` files: PhpStorm's own PHP formatter, reached the way PhpStorm reaches it
 * for the PHP inside a template, without the HTML layer a Phaenon file does not have.
 *
 * PhpStorm's `PhpFormattingModelBuilder` is a template formatter. For a `.php` file it builds the
 * HTML formatting model of the file's template view provider and asks the PHP builder for blocks
 * wherever the HTML tree holds PHP. A `.phn` file has a single-root view provider (see
 * [PhaenonFileViewProvider]), so the PHP builder falls through to its base class, which formats a
 * file of an unknown template structure as one read-only leaf: nothing moved, ever. Reformat Code,
 * auto-indent, the indent of a new line, the `*` of a docblock and the reformatting that follows
 * generated code all went through that leaf.
 *
 * This builder is a [CustomFormattingModelBuilder] engaged for `.phn` files only (the platform
 * asks every custom builder of the language before taking the first one, so `.php` files keep
 * PhpStorm's builder). It hands the top-level PSI of the file to the PHP builder's public
 * `createTemplateFormattingModel`, the same call the template path makes for each PHP region,
 * so every block, indent, wrap, alignment and spacing rule is PhpStorm's, with the PHP code style.
 *
 * The one thing PhpStorm's rules do not know is an erased construct: to them it is a token of an
 * unknown kind (it is not one of PHP's comment types either). [PhaenonFormattingBlock] wraps the
 * block tree and decides the spacing next to an erased leaf; the leaf's own text is a single
 * block and is never changed. A construct is never split or moved off its line: a `<…>` list, an
 * erased return type and a union member stay against what they belong to, and an erased type
 * stays on the line of the parameter it types. Line breaks come only from PhpStorm's rules (brace
 * placement, wrapping at the right margin), as in a `.php` file; the compiler lays out its output
 * itself and maps it back to the `.phn`'s lines (design/line-map.md).
 *
 * `createTemplateFormattingModel` is a public method of PhpStorm's PHP formatter; the formatter's
 * blocks are used as PhpStorm builds them and only wrapped, never subclassed.
 *
 * The PHP code style is the project's, with one change for `.phn` files: Phaenon's canonical layout
 * (design/functions.md §5) puts the opening brace of a function, a method and a closure on the
 * declaration's line, keeps a class-like's on a line of its own, and writes an empty function,
 * method or class-like body as `{}`. PhpStorm's formatter is handed a copy of the settings whose brace placement
 * says so, and the wrapped blocks join an empty body ([ErasedSpacing.emptyBody]); Reformat Code,
 * the indent of Enter and the code an action generates follow it.
 */
class PhaenonFormattingModelBuilder : CustomFormattingModelBuilder {

    private val php = PhpFormattingModelBuilder()

    override fun isEngagedToFormat(context: PsiElement): Boolean =
        PhaenonFiles.isPhaenonFile(context.containingFile?.viewProvider?.virtualFile)

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = canonical(formattingContext.codeStyleSettings, file.project)
        val policy = HtmlPolicy(settings, FormattingDocumentModelImpl.createOn(file))
        val topLevel = file.node.getChildren(null).filter { it.elementType !== TokenType.WHITE_SPACE && it.textLength > 0 }.map { it.psi }
        val model = php.createTemplateFormattingModel(file, settings, policy, topLevel, Indent.getNoneIndent())
            ?: return SimpleTemplateLanguageFormattingModelBuilder().createModel(formattingContext)
        val root = PhaenonFormattingBlock.of(model.rootBlock, ErasedSpacing(file, settings))
        return DocumentBasedFormattingModel(root, file.project, settings, file.fileType, file)
    }

    /**
     * [settings] with Phaenon's brace placement: a class-like's opening brace on a line of its own,
     * a function's, a method's and a closure's on the declaration's line. An empty body of a named
     * function, method or class-like is written `{}` by [ErasedSpacing.emptyBody]; PhpStorm's own
     * options for empty bodies stay as the project sets them, since they reach closures and
     * anonymous classes too, which are laid out as in PHP.
     */
    private fun canonical(settings: CodeStyleSettings, project: Project): CodeStyleSettings {
        val php = settings.getCommonSettings(PhpLanguage.INSTANCE)
        val endOfLine = CommonCodeStyleSettings.END_OF_LINE
        val nextLine = CommonCodeStyleSettings.NEXT_LINE
        if (php.CLASS_BRACE_STYLE == nextLine && php.METHOD_BRACE_STYLE == endOfLine && php.LAMBDA_BRACE_STYLE == endOfLine) return settings
        val copy = CodeStyleSettingsManager.getInstance(project).cloneSettings(settings)
        copy.getCommonSettings(PhpLanguage.INSTANCE).apply {
            CLASS_BRACE_STYLE = nextLine
            METHOD_BRACE_STYLE = endOfLine
            LAMBDA_BRACE_STYLE = endOfLine
        }
        return copy
    }
}

/**
 * The spacing next to an erased construct, which PhpStorm's rules cannot judge.
 *
 * An erased leaf is one of two kinds. Some belong to their neighbour and are written against
 * it: a list in angle brackets (`Box<T>`, `fun map<U>(`, `new Box<int>(`, `implements I<T>`)
 * to the name before it and to the `(` of a call after it; a return type erased whole
 * (`: ?T`, `: list<User>`) to the `)` before it, as a PHP return type is; a union member erased
 * with its separator (`string|list<int>`, `list<T>|null`) to the member it is joined to.
 * Every other erased construct stands where a PHP token
 * would (`list<User> $users`, `$x as T`, `type Name = …;`, `spawn $task`): PhpStorm's rule for that
 * spot applies, except that it never joins the construct to its neighbour where they were apart,
 * which would make one word of two (`$x as T` → `$xas T`) or glue a type to its variable. Where
 * PhpStorm has no rule, a construct apart from its neighbour is one space from it, or the line
 * breaks it was, and one written against its neighbour stays so.
 */
internal class ErasedSpacing(private val file: PsiFile, settings: CodeStyleSettings) {

    private val text: CharSequence = file.viewProvider.contents

    /** One space, or the line breaks there were: what stands between a PHP token and a construct apart from it. */
    private val apart: Spacing = Spacing.createSpacing(1, 1, 0, true, settings.getCommonSettings(PhpLanguage.INSTANCE).KEEP_BLANK_LINES_IN_CODE)

    /** Before an expression body's arrow: one space, or the line break there was, no blank line. */
    val arrow: Spacing = Spacing.createSpacing(1, 1, 0, true, 0)

    fun isErased(block: Block): Boolean = (block as? ASTBlock)?.node?.elementType === PhaenonTokenTypes.ERASED

    /**
     * The spacing of an empty body (design/functions.md §5.1): `{}` one space after the declaration
     * with nothing between the braces. That is a function's or method's body — after `)`, a return
     * type or a `throws` clause, a multi-line parameter list's `)` too — and a class's, interface's,
     * trait's or enum's, after its header (type parameters, `extends`, `implements`, a backing type),
     * whichever line its brace was on. Empty is what PhpStorm's own options mean: only whitespace
     * between the braces; a comment, a statement or a member keeps the body's lines. A closure's and
     * an anonymous class's body are left to PhpStorm, and so is a brace after a line comment, which
     * joining would swallow. Null when [left] and [right] are not such a pair.
     */
    fun emptyBody(left: Block, right: Block): Spacing? {
        val before = leafAt(right.textRange.startOffset) ?: return null
        if (before.node.elementType === PhpTokenTypes.chLBRACE && emptyNamedBody(before)) return ONE_SPACE
        val after = leafAt(left.textRange.endOffset - 1) ?: return null
        if (after.node.elementType === PhpTokenTypes.chLBRACE && before.node.elementType === PhpTokenTypes.chRBRACE &&
            after.parent === before.parent && emptyNamedBody(after)
        ) return GLUED
        return null
    }

    private fun leafAt(offset: Int): PsiElement? = if (offset < 0) null else file.findElementAt(offset)

    /** Whether [brace] belongs to the body of a named function, method or class-like with only whitespace between its braces. */
    private fun emptyNamedBody(brace: PsiElement): Boolean {
        val owner = brace.parent
        val (first, last) = when (owner) {
            is GroupStatement -> {
                val function = owner.parent as? Function ?: return false
                if (function.isClosure) return false
                owner.node.firstChildNode to owner.node.lastChildNode
            }
            // A class-like's braces are its own children, after its header.
            is PhpClass -> {
                if (owner.isAnonymous) return false
                owner.node.findChildByType(PhpTokenTypes.chLBRACE) to owner.node.lastChildNode
            }
            else -> return false
        }
        if (first == null || first.elementType !== PhpTokenTypes.chLBRACE || first.text != "{") return false
        // After a line comment the brace cannot join the declaration's line: the body keeps its lines.
        var previous = PsiTreeUtil.prevLeaf(first.psi, true)
        while (previous is PsiWhiteSpace) previous = PsiTreeUtil.prevLeaf(previous, true)
        if (previous is PsiComment && !PhaenonTokenTypes.isErased(previous)) return false
        if (last == null || last === first || last.elementType !== PhpTokenTypes.chRBRACE) return false
        var node = first.treeNext
        while (node != null && node !== last) {
            if (node.elementType !== TokenType.WHITE_SPACE) return false
            node = node.treeNext
        }
        return true
    }

    /** The erased leaf a block starts with, if it does. */
    private fun firstErased(block: Block): PsiElement? =
        file.findElementAt(block.textRange.startOffset)?.takeIf { PhaenonTokenTypes.isErased(it) }

    /** The erased leaf a block ends with, if it does. */
    private fun lastErased(block: Block): PsiElement? =
        block.textRange.endOffset.takeIf { it > 0 }?.let { file.findElementAt(it - 1) }?.takeIf { PhaenonTokenTypes.isErased(it) }

    /** The spacing between [left] and [right] when either side is an erased construct; null to leave it to PhpStorm. */
    fun between(left: Block, right: Block, phpStorm: () -> Spacing?): Spacing? {
        // Two blocks that meet inside a word are one Phaenon keyword handed to the parser as several
        // PHP tokens (`concurrently` as `function ( )`, `spawn` as `fn ( ) =>`): nothing goes between.
        // PHP tokens never meet so — they would have been lexed as one word.
        // So are the two halves of an expression body's arrow, `{` and `return` to the parser.
        val meet = left.textRange.endOffset
        if (meet == right.textRange.startOffset && meet > 0 && meet < text.length) {
            val a = text[meet - 1]
            val b = text[meet]
            if (a.isLetterOrDigit() && b.isLetterOrDigit() || a == '=' && b == '>') return GLUED
        }
        val after = lastErased(left)
        val before = firstErased(right)
        if (after == null && before == null) return null
        val gap = text.subSequence(left.textRange.endOffset, right.textRange.startOffset)
        val rightText = text.subSequence(right.textRange.startOffset, right.textRange.endOffset)

        if (before != null && attachesLeft(before)) return GLUED
        if (after != null && attachesRight(after, rightText)) return GLUED

        val spacing = phpStorm()
        // No rule for a token of unknown kind: as written when written together, else one space.
        if (spacing == null) return if (gap.isEmpty()) GLUED else apart
        if (gap.isEmpty()) return spacing
        // Apart, and both sides word-like (`$x as T`, `list<User> $users`, `spawn $task`): a rule meant
        // for other tokens could join them, so they stay one space or their line breaks apart. Elsewhere
        // (punctuation on either side, a body's brace) PhpStorm's rule holds.
        val leftChar = text[left.textRange.endOffset - 1]
        val rightChar = rightText.firstOrNull() ?: return spacing
        val wordLikeLeft = leftChar.isLetterOrDigit() || leftChar in "_$\\>)]"
        val wordLikeRight = rightChar.isLetterOrDigit() || rightChar in "_$\\&.?("
        return if (wordLikeLeft && wordLikeRight) apart else spacing
    }

    /** `<…>` after a name, `: T` after `)`, `|T` after a union member. */
    private fun attachesLeft(leaf: PsiElement): Boolean = leaf.text.firstOrNull().let { it == '<' || it == ':' || it == '|' }

    /** `T|` before a union member, `<…>` before the `(` of a call. */
    private fun attachesRight(leaf: PsiElement, right: CharSequence): Boolean {
        val text = leaf.text
        return text.endsWith("|") || (text.startsWith("<") && right.startsWith("("))
    }

    private companion object {
        val GLUED: Spacing = Spacing.createSpacing(0, 0, 0, false, 0)
        val ONE_SPACE: Spacing = Spacing.createSpacing(1, 1, 0, false, 0)
    }
}

/**
 * A block of PhpStorm's PHP formatter seen through the erased-construct rules of [ErasedSpacing]:
 * everything is the wrapped block's (its indent, wrap, alignment, children's attributes for a new
 * line) but the spacing next to an erased leaf.
 *
 * Wrapping, rather than subclassing PhpStorm's blocks, keeps every block type of the PHP formatter
 * (`PhpBlock`, its chained-call and synthetic blocks) under the same rules without depending on
 * their constructors. PhpStorm's blocks keep working on their own children; the formatter engine
 * works on the wrappers, which hand the unwrapped children back to PhpStorm's spacing rules.
 *
 * An erased type before a parameter (`list<User> $users`) is a block of the parameter list beside
 * the parameter's block, where PhpStorm's parser would have made it part of the parameter. So that
 * a wrapped or aligned parameter list moves the pair together, the erased block takes over the
 * wrap and the alignment PhpStorm gave the parameter, and the parameter keeps neither. Inside a
 * promoted parameter (`private readonly list<User> $users`) the erased type is where PhpStorm
 * looks for the parameter's name, the first child after the modifiers and the native type, to
 * align names in columns; that alignment goes to the name.
 */
internal open class PhaenonFormattingBlock protected constructor(
    val delegate: Block,
    private val spacing: ErasedSpacing,
    private val ownWrap: Wrap?,
    private val ownAlignment: Alignment?,
    private val ownIndent: Indent? = null,
) : BlockEx, ExtraRangesProvider {

    /** A block of the AST is one to the engine too: it checks the node of every [ASTBlock] it is given. */
    private class OfNode(delegate: ASTBlock, spacing: ErasedSpacing, wrap: Wrap?, alignment: Alignment?, indent: Indent?) :
        PhaenonFormattingBlock(delegate, spacing, wrap, alignment, indent), ASTBlock {
        override fun getNode(): ASTNode? = (delegate as ASTBlock).node
    }

    companion object {
        fun of(
            delegate: Block,
            spacing: ErasedSpacing,
            wrap: Wrap? = delegate.wrap,
            alignment: Alignment? = delegate.alignment,
            indent: Indent? = null,
        ): PhaenonFormattingBlock =
            if (delegate is ASTBlock && delegate.node != null) OfNode(delegate, spacing, wrap, alignment, indent)
            else PhaenonFormattingBlock(delegate, spacing, wrap, alignment, indent)
    }

    private val delegateNode: ASTNode? get() = (delegate as? ASTBlock)?.node

    /**
     * A `concurrently` block used as a statement is, to PhpStorm, an erased leaf followed by a bare
     * block, and PhpStorm indents a bare block's statements no further than the block. The two are
     * taken together as one statement, the way `if (…) {` is, so the braces stand where the keyword
     * does and the body is one level further in.
     */
    private fun isStatementBlock(erased: Block, next: Block): Boolean {
        if (!spacing.isErased(erased) || !erased.nodeText().startsWith("concurrently")) return false
        val node = (next as? ASTBlock)?.node ?: return false
        return node.elementType in PhpElementTypes.ANY_GROUP_STATEMENT || node.psi is GroupStatement
    }

    private fun Block.nodeText(): String = (this as? ASTBlock)?.node?.text.orEmpty()

    /**
     * An expression body, `=> expr;`: to PhpStorm a block whose `{` is the arrow's first character.
     * It stays where it was written — one space after the signature, or on a line of its own one
     * level in, the arrow starting the continuation line — whatever the brace placement says.
     */
    private fun isExpressionBody(block: Block): Boolean {
        val node = (block as? ASTBlock)?.node ?: return false
        if (node.elementType !in PhpElementTypes.ANY_GROUP_STATEMENT) return false
        val brace = node.firstChildNode ?: return false
        return brace.elementType === PhpTokenTypes.chLBRACE && brace.textLength == 1 && brace.text == "="
    }

    private val children: List<Block> by lazy {
        val blocks = wrapChildren(delegate.subBlocks).map { block ->
            if (block is PhaenonFormattingBlock && isExpressionBody(block.delegate)) of(block.delegate, spacing, block.wrap, block.alignment, Indent.getNormalIndent())
            else block
        }
        val out = ArrayList<Block>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            val next = blocks.getOrNull(i + 1)
            if (next is PhaenonFormattingBlock && block is PhaenonFormattingBlock && isStatementBlock(block.delegate, next.delegate)) {
                val keyword = of(block.delegate, spacing, null, null, Indent.getNoneIndent())
                out.add(StatementBlock(keyword, of(next.delegate, spacing, next.wrap, next.alignment, Indent.getNoneIndent()), spacing, block.indent, block.wrap, block.alignment))
                i += 2
                continue
            }
            out.add(block)
            i++
        }
        out
    }

    private fun wrapChildren(blocks: List<Block>): List<Block> {
        val insideParameter = delegateNode?.psi is Parameter
        val out = ArrayList<Block>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            val next = blocks.getOrNull(i + 1)
            if (next != null && spacing.isErased(block) && typesTheNext(block, next)) {
                out.add(of(block, spacing, next.wrap ?: block.wrap, next.alignment ?: block.alignment))
                out.add(of(next, spacing, null, null))
                i += 2
                continue
            }
            if (next != null && insideParameter && spacing.isErased(block) && block.alignment != null && next.alignment == null) {
                // PhpStorm aligns a parameter's first child after its modifiers and native type: the name.
                out.add(of(block, spacing, block.wrap, null))
                out.add(of(next, spacing, next.wrap, block.alignment))
                i += 2
                continue
            }
            out.add(of(block, spacing))
            i++
        }
        return out
    }

    /**
     * An erased type written before a parameter on the same line: the parameter's type, outside the
     * parameter's PSI because the parser never saw it. (Inside a parameter, after its modifiers, the
     * erased type and the variable are children of the parameter's own block, as PHP's are.)
     */
    private fun typesTheNext(erased: Block, next: Block): Boolean {
        val leaf = (erased as ASTBlock).node ?: return false
        val parameter = (next as? ASTBlock)?.node ?: return false
        if (parameter.psi !is Parameter) return false
        val between = leaf.treeNext
        return between === parameter || (between?.elementType === TokenType.WHITE_SPACE && !between.textContains('\n') && between.treeNext === parameter)
    }

    override fun getTextRange(): TextRange = delegate.textRange

    override fun getSubBlocks(): List<Block> = children

    override fun getWrap(): Wrap? = ownWrap

    override fun getIndent(): Indent? = ownIndent ?: delegate.indent

    override fun getAlignment(): Alignment? = ownAlignment

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        // A statement block is the plugin's: PhpStorm is asked about the leaf or block at its edge.
        val left = ((child1 as? StatementBlock)?.body ?: child1).let { (it as? PhaenonFormattingBlock)?.delegate ?: it }
        val right = ((child2 as? StatementBlock)?.keyword ?: child2).let { (it as? PhaenonFormattingBlock)?.delegate ?: it }
        if (left == null) return delegate.getSpacing(null, right)
        if (isExpressionBody(right)) return spacing.arrow
        spacing.emptyBody(left, right)?.let { return it }
        return spacing.between(left, right) { delegate.getSpacing(left, right) } ?: delegate.getSpacing(left, right)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = delegate.getChildAttributes(newChildIndex)

    override fun isIncomplete(): Boolean = delegate.isIncomplete

    override fun isLeaf(): Boolean = delegate.isLeaf

    override fun getLanguage(): Language? = (delegate as? BlockEx)?.language

    override fun getExtraRangesToFormat(info: com.intellij.formatting.FormattingRangesInfo): List<TextRange>? =
        (delegate as? ExtraRangesProvider)?.getExtraRangesToFormat(info)

    override fun getDebugName(): String = "Phaenon(" + (delegate.debugName ?: delegate.javaClass.simpleName) + ")"

    override fun toString(): String = getDebugName() + " " + textRange
}

/**
 * A `concurrently` block used as a statement: its erased keyword and options, and the braces
 * PhpStorm parses as a bare block, as one block that stands where a statement does — the indent,
 * wrap and alignment the keyword had — with the body's block inside it unindented, as an `if`'s.
 */
internal class StatementBlock(
    val keyword: PhaenonFormattingBlock,
    val body: PhaenonFormattingBlock,
    private val spacing: ErasedSpacing,
    private val indent: Indent?,
    private val wrap: Wrap?,
    private val alignment: Alignment?,
) : Block {

    override fun getTextRange(): TextRange = TextRange(keyword.textRange.startOffset, body.textRange.endOffset)

    override fun getSubBlocks(): List<Block> = listOf(keyword, body)

    override fun getWrap(): Wrap? = wrap

    override fun getIndent(): Indent? = indent

    override fun getAlignment(): Alignment? = alignment

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        return spacing.between(keyword.delegate, body.delegate) { null }
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)

    override fun isIncomplete(): Boolean = body.isIncomplete

    override fun isLeaf(): Boolean = false
}
