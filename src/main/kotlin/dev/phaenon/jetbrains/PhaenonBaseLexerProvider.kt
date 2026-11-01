package dev.phaenon.jetbrains

import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.psi.tree.IElementType
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.php.lang.lexer.PhpBaseLexerProvider
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer

/**
 * Supplies the lexer the *editor* highlighter uses for `.phn` files.
 *
 * Syntax highlighting in the editor does not go through the parser definition: PhpStorm's
 * editor highlighter provider is keyed by the PHP file type and builds its own lexer. It asks
 * `com.jetbrains.php.baseLexerProvider` extensions first, which lets us hand it the same
 * open-tag-optional lexer the parser uses, so what you see while typing matches the PSI.
 * [PhaenonHighlightingLexer] colours `fun` as the keyword it is.
 */
class PhaenonBaseLexerProvider : PhpBaseLexerProvider {

    override fun getBaseLexer(project: Project?, file: VirtualFile?): Lexer? =
        if (PhaenonFiles.isPhaenonFile(file)) PhaenonHighlightingLexer(PhpWithOptionalOpenTagLexer()) else null
}

/**
 * The editor's lexer with `fun` read as the `function` keyword where it declares something:
 * before a name, or `&` and a name. [PhaenonLexer] does the same for the parser.
 */
class PhaenonHighlightingLexer(delegate: Lexer) : DelegateLexer(delegate) {

    override fun getTokenType(): IElementType? {
        val type = super.getTokenType()
        if (type !== PhpTokenTypes.IDENTIFIER) return type
        val text = bufferSequence
        val start = tokenStart
        val end = tokenEnd
        return when (text.subSequence(start, end).toString()) {
            "fun" -> if (declaresName(text, end)) PhpTokenTypes.kwFUNCTION else type
            // Coloured as keywords where they are ones: not after `->` or `::`, and `spawn = 1` is a constant.
            "concurrently" -> if (afterMemberAccess(text, start)) type else PhpTokenTypes.kwFUNCTION
            "spawn" -> if (afterMemberAccess(text, start) || assigned(text, end)) type else PhpTokenTypes.kwFUNCTION
            else -> type
        }
    }

    private fun declaresName(text: CharSequence, end: Int): Boolean {
        var i = end
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length && text[i] == '&') {
            i++
            while (i < text.length && text[i].isWhitespace()) i++
        }
        return i < text.length && (text[i].isLetter() || text[i] == '_')
    }

    private fun afterMemberAccess(text: CharSequence, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && (text[i] == '>' || text[i] == ':')
    }

    private fun assigned(text: CharSequence, end: Int): Boolean {
        var i = end
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '=' && (i + 1 >= text.length || text[i + 1] != '=' && text[i + 1] != '>')
    }
}
