package dev.phaenon.jetbrains

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * PhpStorm's encoding of a closure signature, `\Closure<#ᤓᤓ\string,\bool>`: one part per
 * parameter, then the return type, a union's members joined in one part. Built with PhpStorm's own
 * serializer, as [PhaenonTypeText] builds it.
 */
fun closure(parameters: List<String>, vararg returns: String): String =
    PhpType.createParametrizedType(
        PhpType._CLOSURE,
        parameters.map { PhpExpressionImpl.serializeAdvancedClosureParameter("", it) } +
            returns.sorted().joinToString(PhpExpressionImpl.CALLABLE_PARAMS_TYPES_SEPARATOR),
    )

/** The Ctrl+hover hint at [needle] + [shift] in the fixture's file, as the popup shows it. */
fun CodeInsightTestFixture.hint(needle: String, shift: Int): String? {
    val offset = file.text.indexOf(needle).also { check(it >= 0) { needle } } + shift
    val target = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, offset).single()
    return target.computeDocumentationHint()?.let(::popupText)
}

/** The quick documentation at [needle] + [shift] in the fixture's file, as the popup shows it. */
fun CodeInsightTestFixture.quickDoc(needle: String, shift: Int): String {
    val offset = file.text.indexOf(needle).also { check(it >= 0) { needle } } + shift
    val target = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, offset).single()
    return popupText(computeDocumentationBlocking(target.createPointer())?.html.orEmpty())
}

/** The text a documentation popup shows: tags dropped, entities decoded, whitespace runs as one space. */
fun popupText(html: String): String = html
    .replace("<br>", " ")
    .replace(Regex("<[^>]+>"), "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")
    .trim()
