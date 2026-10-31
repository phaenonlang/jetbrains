package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement

/**
 * Drops the PhpStorm findings that Phaenon's compilation model provokes in a `.phn` file.
 *
 * A `readonly` property must have a type in PHP, and PhpStorm's annotator says so when it sees
 * `private readonly $items`. In a Phaenon file the property had one — `list<Item>`, say — and
 * [PhaenonLexer] erased it. The annotator cannot be told; its message can be filtered, and only
 * where the property's erased type is still there in the whitespace.
 *
 * "Other declarations of class 'X' exist at X.php" is PhpStorm seeing the `.php` the compiler
 * wrote from the class's own `.phn`: not a conflict, the same class twice. The finding stays when
 * some other declaration is hand-written.
 */
class PhaenonHighlightFilter : HighlightInfoFilter {

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || !PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return true
        val description = info.description ?: return true
        if (description.startsWith(OTHER_DECLARATIONS_PREFIX)) return otherDeclarationIsHandWritten(info, file)
        if (description != READONLY_NEEDS_TYPE && description != ASYMMETRIC_NEEDS_TYPE) return true
        val leaf = file.findElementAt(info.startOffset) ?: return true
        val declaration = PsiTreeUtil.getParentOfType(leaf, Field::class.java, Parameter::class.java) as? PhpNamedElement ?: return true
        return PhaenonErasedTypes.beforeName(declaration).isEmpty()
    }

    private fun otherDeclarationIsHandWritten(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return true
        val klass = PsiTreeUtil.getParentOfType(leaf, PhpClass::class.java) ?: return true
        val here = file.viewProvider.virtualFile
        return PhpIndex.getInstance(file.project).getAnyByFQN(klass.fqn).any { other ->
            other.containingFile.viewProvider.virtualFile != here && !PhaenonFiles.isGeneratedOutput(other.containingFile)
        }
    }

    private companion object {
        val READONLY_NEEDS_TYPE: String = PhpBundle.message("readonly.property.must.have.type")
        val ASYMMETRIC_NEEDS_TYPE: String = PhpBundle.message("php.annotator.property.with.asymmetric.visibility.must.have.type")

        /** The message up to the class name, which the inspection fills in for `#ref`. */
        val OTHER_DECLARATIONS_PREFIX: String =
            PhpBundle.message("inspection.php.undefined.class.other.declaration.exists", "").substringBefore("#ref")
    }
}
