package dev.phaenon.jetbrains

import com.intellij.lexer.Lexer
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
 */
class PhaenonBaseLexerProvider : PhpBaseLexerProvider {

    override fun getBaseLexer(project: Project?, file: VirtualFile?): Lexer? =
        if (PhaenonFiles.isPhaenonFile(file)) PhpWithOptionalOpenTagLexer() else null
}
