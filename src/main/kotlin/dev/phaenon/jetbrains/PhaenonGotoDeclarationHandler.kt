package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference

/**
 * Sends "go to declaration" on `$xs->map(...)` to `\Phaenon\Seq::map()`.
 *
 * The method form resolves to the editor-only pseudo-classes under `\Phaenon\Internal` (see
 * [PhaenonTypeText]); the code that runs is the static function of the stdlib class in the
 * `phaenon/runtime` package. When that package is in the project, navigation lands on the real
 * function; without it, PhpStorm's default navigation to the bundled stub stays.
 */
class PhaenonGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null || !PhaenonErasedTypes.isPhaenon(sourceElement)) return null
        val reference = PsiTreeUtil.getParentOfType(sourceElement, MethodReference::class.java) ?: return null
        if (reference.nameNode?.psi != sourceElement && reference.nameNode?.textRange?.contains(offset) != true) return null
        val index = PhpIndex.getInstance(sourceElement.project)
        val targets = ArrayList<PsiElement>()
        for (result in reference.multiResolve(false)) {
            val method = result.element as? Method ?: continue
            val runtimeClass = RUNTIME_CLASSES[method.containingClass?.fqn] ?: continue
            for (klass in index.getClassesByFQN(runtimeClass)) {
                klass.findOwnMethodByName(method.name)?.let(targets::add)
            }
        }
        return if (targets.isEmpty()) null else targets.toTypedArray()
    }

    private companion object {
        val RUNTIME_CLASSES: Map<String, String> = mapOf(
            PhaenonTypeText.LIST_METHODS to "\\Phaenon\\Seq",
            PhaenonTypeText.MAP_METHODS to "\\Phaenon\\Map",
            PhaenonTypeText.STR_METHODS to "\\Phaenon\\Str",
            PhaenonTypeText.BYTES_METHODS to "\\Phaenon\\Bytes",
        )
    }
}
