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
 *
 * That is also why the stubs of a `.phn` file carry PhpStorm's stub version and not the plugin's:
 * the platform keys the version by language, through the PHP parser definition's file node type
 * ([PSI_VERSION] explains what the plugin does instead).
 */
object PhaenonFileElementType : IFileElementType("phaenon.FILE", PhpLanguage.INSTANCE) {

    /**
     * The version of the PSI a `.phn` file produces. **Bump it whenever [PhaenonLexer], the
     * erasures, [PhaenonPsiBuilder] or [PhaenonParserDefinition] change which elements a `.phn`
     * parses into**: an erasure added or removed, a keyword handed to the parser differently, a
     * body no longer lazy. Bump it as well when [PhaenonTypeProvider] answers differently for a
     * declaration: PhpStorm stores that answer with the declaration's stub, so an old index would
     * keep the old type (3: type parameters read as their bounds, `class<T>` as
     * `class-string<Bound>`, closure types with their signature; 4: `class<T>` without a `string`
     * member, PHP 8.6 placeholders in argument lists; 5: `concurrently` used as a value and `spawn`
     * as closures; 6: `throws` clauses erased, expression bodies as blocks; 7: `list`, `map` and
     * `bytes` typed as the array and the string they erase to, D20; 8: `concurrently` and `spawn`
     * plain identifiers again, D25).
     *
     * The stub trees PhpStorm stores for `.phn` files are versioned by the PHP file type (the
     * platform asks the language's parser definition, never the file's content element type), so
     * a plugin update that changes the PSI leaves stale stub trees in the index and the next AST
     * load fails with "PSI and index do not match: stub count doesn't match stubbed node length".
     * [PhaenonReindexActivity] re-indexes the `.phn` files of a project once this number differs
     * from the one it stored.
     */
    const val PSI_VERSION: Int = 8

    override fun parseContents(chameleon: ASTNode): ASTNode? {
        val project = chameleon.psi.project
        val php = LanguageParserDefinitions.INSTANCE.forLanguage(PhpLanguage.INSTANCE)
        val builder = PsiBuilderImpl(project, PhaenonParserDefinition(php), PhaenonLexer(), chameleon, chameleon.chars)

        return php.createParser(project).parse(this, PhaenonPsiBuilder(project, builder)).firstChildNode
    }
}
