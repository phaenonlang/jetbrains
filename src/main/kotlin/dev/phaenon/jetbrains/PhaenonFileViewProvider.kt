package dev.phaenon.jetbrains

import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.FileViewProviderFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.source.PsiFileImpl
import com.jetbrains.php.lang.PhpFileViewProviderFactory
import com.jetbrains.php.lang.PhpLanguage

/**
 * View provider for `.phn` files: a single PHP root whose AST is parsed through
 * [PhaenonFileElementType].
 *
 * The PSI file is PhpStorm's regular `PhpFileImpl` in the regular PHP language, so every piece of
 * PhpStorm that asks "is this PHP?" gets the answer it expects. Swapping the content element type
 * right after the file is created is the same hook PhpStorm's own template view provider uses to
 * give the HTML side of a `.php` file its parser; the AST has not been built yet at that point.
 *
 * PhpStorm's provider is a template view provider that also hosts an HTML tree built from the
 * inline-HTML tokens. A Phaenon file has no HTML, and giving the HTML parser a whole PHP file to
 * chew on would only produce spurious HTML inspections, so this one is single-rooted.
 */
class PhaenonFileViewProvider(manager: PsiManager, file: VirtualFile, eventSystemEnabled: Boolean) :
    SingleRootFileViewProvider(manager, file, eventSystemEnabled, PhpLanguage.INSTANCE) {

    override fun createFile(lang: Language): PsiFile? {
        val file = super.createFile(lang) ?: return null
        if (lang === PhpLanguage.INSTANCE && file is PsiFileImpl) {
            file.contentElementType = PhaenonFileElementType
        }
        return file
    }

    override fun createCopy(copy: VirtualFile): SingleRootFileViewProvider =
        PhaenonFileViewProvider(manager, copy, false)
}

/**
 * Registered for the PHP language ahead of PhpStorm's own factory. It claims `.phn` files and
 * hands every other PHP file straight to PhpStorm's factory, so plain PHP is untouched.
 */
class PhaenonFileViewProviderFactory : FileViewProviderFactory {

    private val phpFactory = PhpFileViewProviderFactory()

    override fun createFileViewProvider(
        file: VirtualFile,
        language: Language?,
        manager: PsiManager,
        eventSystemEnabled: Boolean,
    ): FileViewProvider =
        if (PhaenonFiles.isPhaenonFile(file)) {
            PhaenonFileViewProvider(manager, file, eventSystemEnabled)
        } else {
            phpFactory.createFileViewProvider(file, language, manager, eventSystemEnabled)
        }
}
