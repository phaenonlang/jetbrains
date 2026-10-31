package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpUse

/**
 * Drops the PhpStorm findings that Phaenon's compilation model provokes in a `.phn` file.
 *
 * A `readonly` property must have a type in PHP, and PhpStorm's annotator says so when it sees
 * `private readonly $items`. In a Phaenon file the property had one — `list<Item>`, say — and
 * [PhaenonLexer] erased it. The annotator cannot be told; its message can be filtered, and only
 * where the property's erased type is still there in the whitespace. The "missing type
 * declaration" inspections for properties, parameters and return types are the same case.
 *
 * An import used only inside an erased type (`use App\Event\DomainEvent;` for
 * `list<DomainEvent>`) is "never used" to PhpStorm, which cannot see into the erased text.
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
        if (UNUSED_IMPORT.any { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) {
            return !importIsUsedInAnErasedType(info, file)
        }
        if (description == RETURN_TYPE_MISSING) {
            val leaf = file.findElementAt(info.startOffset) ?: return true
            val function = PsiTreeUtil.getParentOfType(leaf, Function::class.java) ?: return true
            return PhaenonErasedTypes.returnType(function).isEmpty()
        }
        if (description !in MEMBER_NEEDS_TYPE) return true
        val leaf = file.findElementAt(info.startOffset) ?: return true
        val declaration = PsiTreeUtil.getParentOfType(leaf, Field::class.java, Parameter::class.java) as? PhpNamedElement ?: return true
        return PhaenonErasedTypes.beforeName(declaration).isEmpty()
    }

    /** `use App\Event\DomainEvent;` is used by `list<DomainEvent>`, which PhpStorm cannot see. */
    private fun importIsUsedInAnErasedType(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val use = PsiTreeUtil.getParentOfType(leaf, PhpUse::class.java, false) ?: return false
        val short = use.aliasName ?: use.fqn.substringAfterLast('\\')
        return short.isNotEmpty() && PhaenonErasedNames.mentions(file, short)
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
        val MEMBER_NEEDS_TYPE: Set<String> = setOf(
            PhpBundle.message("readonly.property.must.have.type"),
            PhpBundle.message("php.annotator.property.with.asymmetric.visibility.must.have.type"),
            PhpBundle.message("inspection.missing.field.type"),
            PhpBundle.message("inspection.php.missing.param.type.description"),
        )
        val RETURN_TYPE_MISSING: String = PhpBundle.message("inspection.php.missing.return.type.description")

        /** "Import '#ref' is never used" / "is unnecessary", split around the name the inspection fills in. */
        val UNUSED_IMPORT: List<Pair<String, String>> = listOf("inspection.php.unused.alias.description", "inspection.php.unused.alias.description2")
            .map { PhpBundle.message(it) }
            .map { it.substringBefore("#ref") to it.substringAfter("#ref") }

        /** The message up to the class name, which the inspection fills in for `#ref`. */
        val OTHER_DECLARATIONS_PREFIX: String =
            PhpBundle.message("inspection.php.undefined.class.other.declaration.exists", "").substringBefore("#ref")
    }
}
