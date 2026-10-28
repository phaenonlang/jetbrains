package dev.phaenon.jetbrains

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiBuilderFactory
import com.intellij.psi.ParsingDiagnostics
import com.intellij.psi.tree.IFileElementType
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer

/**
 * The root AST node of a Phaenon file.
 *
 * It is a PHP file element type in every respect but one: it feeds the PHP parser from a lexer
 * that starts in scripting mode. PhpStorm's own `PhpWithOptionalOpenTagLexer` (the one it uses for
 * PHP injected into strings) peeks at the first token and only stays in HTML mode when the file
 * really begins with an open tag, which is exactly the Phaenon contract: PHP code from byte 0.
 *
 * The parse itself mirrors what PhpStorm does for its code fragments: build a `PsiBuilder` with
 * an explicit lexer and hand it to the stock PHP parser. Everything below the root is therefore
 * ordinary PHP PSI, produced by PhpStorm, and the stub builder, indices, inspections and
 * refactorings never learn that the file was special.
 */
object PhaenonFileElementType : IFileElementType("phaenon.FILE", PhpLanguage.INSTANCE) {

    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val project = chameleon.psi.project
        val builder = PsiBuilderFactory.getInstance()
            .createBuilder(project, chameleon, PhpWithOptionalOpenTagLexer(), PhpLanguage.INSTANCE, chameleon.chars)
        val parser = LanguageParserDefinitions.INSTANCE.forLanguage(PhpLanguage.INSTANCE).createParser(project)

        val startedAt = System.nanoTime()
        val root = parser.parse(this, builder)
        ParsingDiagnostics.registerParse(builder, PhpLanguage.INSTANCE, System.nanoTime() - startedAt)

        return root.firstChildNode
    }
}
