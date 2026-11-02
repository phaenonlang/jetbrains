package dev.phaenon.jetbrains

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.LanguageParameterInfo
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * Parameter info for a call of a Phaenon function or method whose parameters have erased types,
 * in Phaenon's spelling: `users: class<User>, id: \App\AggregateId` where PhpStorm prints
 * `users: string, …`.
 *
 * PhpStorm's parameter info prints each parameter from the type it holds, through a presenter
 * that is internal to it, so a `class<T>` parameter reads `string` and a `list<User>` reads
 * `\App\User[]|\Phaenon\Internal\ListMethods`. The platform asks the parameter info handlers of
 * a language in order and takes the first that finds a call; this one is registered before
 * PhpStorm's, takes a call only when one of its targets is such a Phaenon function, and hands
 * everything else — finding the argument list, following the caret, named arguments — to
 * PhpStorm's handler, found through the extension point by its class name. Only the text of
 * each parameter is this handler's: PhpStorm's layout (`[`optional`]`, `&`, `...`, `name: type`,
 * ` = default`) with the type as the source writes it.
 */
class PhaenonParameterInfoHandler : ParameterInfoHandler<PsiElement, Any> {

    /** PhpStorm's handler for PHP, or null when the PHP plugin's internals have moved. */
    private val php: ParameterInfoHandler<PsiElement, Any>?
        get() {
            @Suppress("UNCHECKED_CAST")
            return LanguageParameterInfo.INSTANCE.allForLanguage(PhpLanguage.INSTANCE)
                .firstOrNull { it.javaClass.name == PHP_HANDLER } as? ParameterInfoHandler<PsiElement, Any>
        }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val owner = php?.findElementForParameterInfo(context) ?: return null
        // PhpStorm's handler has set its items, the call's targets; when none is a Phaenon function
        // with an erased parameter type, PhpStorm's handler is asked next and shows its own.
        if (context.itemsToShow.orEmpty().none { phaenonFunction(it) != null }) return null
        return owner
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        php?.findElementForUpdatingParameterInfo(context)

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        php?.updateParameterInfo(parameterOwner, context)
    }

    override fun updateUI(p: Any, context: ParameterInfoUIContext) {
        val function = phaenonFunction(p)
        if (function == null) {
            php?.updateUI(p, context)
            return
        }
        if (!function.isValid) {
            context.isUIComponentEnabled = false
            return
        }
        val full = CodeInsightSettings.getInstance().SHOW_FULL_SIGNATURES_IN_PARAMETER_INFO
        val text = StringBuilder()
        if (full) text.append(function.name).append('(')
        val current = context.currentParameterIndex.coerceAtLeast(0)
        val parameters = function.parameters
        var highlightStart = -1
        var highlightEnd = -1
        if (parameters.isEmpty()) text.append("no parameters")
        parameters.forEachIndexed { i, parameter ->
            if (i > 0) text.append(", ")
            val highlighted = context.isUIComponentEnabled &&
                (i == current || i == parameters.size - 1 && parameter.isVariadic && current >= parameters.size)
            if (highlighted) highlightStart = text.length
            text.append(presentation(parameter))
            if (highlighted) highlightEnd = text.length
        }
        if (full) {
            text.append(')')
            PhaenonErasedTypes.writtenReturnType(function)?.let { text.append(": ").append(it) }
        }
        context.setupUIComponentPresentation(text.toString(), highlightStart, highlightEnd, false, false, false, context.defaultParameterColor)
    }

    /** PhpStorm's presentation of a parameter, with the type as written where it was erased. */
    private fun presentation(parameter: Parameter): String {
        val out = StringBuilder()
        if (parameter.isOptional) out.append('[')
        if (parameter.isPassByRef) out.append('&')
        if (parameter.isVariadic) out.append("...")
        out.append(parameter.name)
        val type = if (PhaenonErasedTypes.typeLeaf(parameter) != null) {
            PhaenonErasedTypes.writtenType(parameter)
        } else {
            parameter.type.global(parameter.project).types.filterNot { it.startsWith("?") }.joinToString("|").ifEmpty { null }
        }
        if (type != null) out.append(": ").append(type)
        if (parameter.isOptional) {
            parameter.defaultValuePresentation?.let { out.append(" = ").append(it) }
            out.append(']')
        }
        return out.toString()
    }

    /** The Phaenon function an item of PhpStorm's stands for, when it has a parameter with an erased type. */
    private fun phaenonFunction(item: Any?): Function? {
        val element = (item as? ResolveResult)?.element ?: item as? PsiElement
        val function = when (element) {
            is Function -> element
            is PhpClass -> element.constructor
            else -> null
        } ?: return null
        if (!PhaenonErasedTypes.isPhaenon(function)) return null
        return function.takeIf { f -> f.parameters.any { PhaenonErasedTypes.typeLeaf(it) != null } }
    }

    private companion object {
        const val PHP_HANDLER = "com.jetbrains.php.lang.PhpParameterInfoHandler"
    }
}
