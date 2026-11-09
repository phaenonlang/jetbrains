package dev.phaenon.jetbrains

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.lang.documentation.QuickDocHighlightingHelper.appendStyledSignatureFragment
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.highlighter.PhpHighlightingData
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpAttributesList
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpReturnType
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/**
 * Quick documentation and the Ctrl+hover hint of Phaenon declarations, in Phaenon's spelling.
 *
 * PhpStorm's documentation prints the type it holds, and it prints a class-string as `string`:
 * a declared `class<T>` read `$class: string`, and before that `string|string`. It builds a
 * function's definition line from the PSI, so an erased type was missing from a parameter
 * (`make($users)`) or pasted in raw, its `<T>` swallowed by the HTML (`aggregateClass(): class:
 * string`). PhpStorm offers no hook into either: its type printer is internal to its
 * documentation provider. What the platform does offer is the documentation provider itself,
 * asked in order (this one comes first for PHP), so for a Phaenon element this provider writes
 * the definition line and keeps the rest of PhpStorm's popup — the class it belongs to, the
 * docblock sections, the source file:
 *
 * - a function or method declared in a `.phn` with an erased part in its signature (a type
 *   parameter list, a parameter type, the return type, a `throws` clause): the signature as written,
 *   `abstract protected static fun aggregateClass(): class<T>`, `fun load(Id $id): T throws NotFound`;
 * - a parameter or property declared with an erased type: `$users: class<User>`, the type as
 *   written;
 * - a variable in a `.phn` file whose type holds a class-string or a closure signature: that type
 *   printed back in Phaenon's spelling ([PhaenonTypePresentation]),
 *   `$class: class<EventSourcedAggregate>` — what PhpStorm holds, `T` read as its bound.
 *
 * Everything else, and every element of a `.php` file, is left to PhpStorm. If PhpStorm's popup
 * stops having the definition block this provider replaces, its own popup is shown unchanged.
 */
class PhaenonDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null
        return definition(element, originalElement, hint = true)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null
        val definition = definition(element, originalElement, hint = false) ?: return null
        val php = othersGenerateDoc(element, originalElement) ?: return null
        val start = php.indexOf(DEFINITION_START)
        if (start < 0) return null
        val end = php.indexOf(DEFINITION_END, start)
        if (end < 0) return null
        return php.substring(0, start) + DEFINITION_START + definition + php.substring(end)
    }

    /** What the providers after this one say, PhpStorm's among them. */
    private fun othersGenerateDoc(element: PsiElement, originalElement: PsiElement?): String? =
        LanguageDocumentation.INSTANCE.allForLanguage(PhpLanguage.INSTANCE)
            .asSequence()
            .filter { it !is PhaenonDocumentationProvider }
            .firstNotNullOfOrNull { provider: DocumentationProvider -> provider.generateDoc(element, originalElement) }

    /** The definition line for [element], styled; null when it is not a Phaenon element PhpStorm prints wrongly. */
    private fun definition(element: PsiElement, originalElement: PsiElement?, hint: Boolean): String? {
        if (!element.isValid) return null
        return when {
            element is Function && !element.isClosure && PhaenonErasedTypes.isPhaenon(element) -> function(element, hint)
            (element is Parameter || element is Field && !element.isConstant) && PhaenonErasedTypes.isPhaenon(element) ->
                declaration(element as PhpNamedElement, hint) ?: variable(originalElement, hint)
            element is PhpNamedElement -> variable(originalElement, hint)
            else -> null
        }
    }

    /** `$users: class<User>` for a parameter or property declared with an erased type. */
    private fun declaration(declaration: PhpNamedElement, hint: Boolean): String? {
        if (PhaenonErasedTypes.typeLeaf(declaration) == null) return null
        val type = PhaenonErasedTypes.writtenType(declaration) ?: return null
        val out = StringBuilder()
        styled(out, "$" + declaration.name, if (declaration is Field) PhpHighlightingData.INSTANCE_FIELD else PhpHighlightingData.PARAMETER)
        out.append(if (hint) " : " else ": ")
        styled(out, type, PhpHighlightingData.PRIMITIVE_TYPE_HINT)
        return out.toString()
    }

    /** `$class: class<EventSourcedAggregate>`: a variable of a `.phn` file whose type PhpStorm would print as `string`. */
    private fun variable(originalElement: PsiElement?, hint: Boolean): String? {
        val variable = variableAt(originalElement) ?: return null
        if (!PhaenonErasedTypes.isPhaenon(variable)) return null
        val type = variable.globalType
        if (!PhaenonTypePresentation.hasPhaenonMembers(type)) return null
        val out = StringBuilder()
        styled(out, "$" + variable.name, PhpHighlightingData.VAR)
        out.append(if (hint) " : " else ": ")
        type(out, type)
        return out.toString()
    }

    /** The variable the caret is on: the original element itself, or the `$name` token in it. */
    private fun variableAt(originalElement: PsiElement?): Variable? {
        val element = originalElement ?: return null
        if (element is Variable) return element
        if (PsiUtilCore.getElementType(element) === PhpTokenTypes.VARIABLE) return element.parent as? Variable
        return null
    }

    /**
     * The signature of a Phaenon function as written: modifiers, `fun`, name, type parameters,
     * one parameter per line as PhpStorm lays them out (all on one line in the hint), the
     * return type. Without a written return type, the one PhpStorm infers.
     */
    private fun function(function: Function, hint: Boolean): String? {
        val parameters = function.parameters
        val erased = PhaenonErasedTypes.writtenTypeParameters(function) != null ||
            PhaenonErasedTypes.returnTypeLeaf(function) != null ||
            PhaenonErasedTypes.throwsClause(function) != null ||
            parameters.any { PhaenonErasedTypes.typeLeaf(it) != null }
        if (!erased) return null
        val out = StringBuilder()
        if (!hint) {
            for (attributes in PsiTreeUtil.getChildrenOfTypeAsList(function, PhpAttributesList::class.java)) {
                styled(out, attributes.text.replace(Regex("\\s+"), " "), PhpHighlightingData.KEYWORD)
                out.append("<br>")
            }
        }
        function.node.findChildByType(PhpElementTypes.MODIFIER_LIST)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            styled(out, it.replace(Regex("\\s+"), " "), PhpHighlightingData.KEYWORD)
            out.append(' ')
        }
        styled(out, function.node.findChildByType(PhpTokenTypes.kwFUNCTION)?.text ?: "function", PhpHighlightingData.KEYWORD)
        out.append(' ')
        styled(out, function.name, PhpHighlightingData.FUNCTION)
        PhaenonErasedTypes.writtenTypeParameters(function)?.let { styled(out, it, PhpHighlightingData.PRIMITIVE_TYPE_HINT) }
        styled(out, "(", PhpHighlightingData.PARENTHESES)
        val indent = if (hint) "" else "<br>" + "&nbsp;".repeat(4)
        parameters.forEachIndexed { i, parameter ->
            if (i > 0) {
                styled(out, ",", PhpHighlightingData.COMMA)
                if (hint) out.append(' ')
            }
            out.append(indent)
            parameter(out, parameter)
        }
        if (!hint && parameters.isNotEmpty()) out.append("<br>")
        styled(out, ")", PhpHighlightingData.PARENTHESES)
        val written = PhaenonErasedTypes.writtenReturnType(function)
        val native = PsiTreeUtil.getChildOfType(function, PhpReturnType::class.java)?.type
        if (PhaenonErasedTypes.returnTypeLeaf(function) == null && native != null && !native.isEmpty) {
            out.append(": ")
            type(out, native)
        } else if (written != null) {
            out.append(": ")
            styled(out, written, PhpHighlightingData.PRIMITIVE_TYPE_HINT)
        } else {
            val inferred = function.type.global(function.project)
            if (!inferred.isEmpty) {
                out.append(": ")
                type(out, inferred)
            }
        }
        val throws = PhaenonErasedTypes.throwsNames(function)
        if (throws.isNotEmpty()) {
            out.append(' ')
            styled(out, "throws", PhpHighlightingData.KEYWORD)
            out.append(' ')
            styled(out, throws.joinToString("|"), PhpHighlightingData.PRIMITIVE_TYPE_HINT)
        }
        return out.toString()
    }

    private fun parameter(out: StringBuilder, parameter: Parameter) {
        for (attributes in PsiTreeUtil.getChildrenOfTypeAsList(parameter, PhpAttributesList::class.java)) {
            styled(out, attributes.text.replace(Regex("\\s+"), " "), PhpHighlightingData.KEYWORD)
            out.append(' ')
        }
        parameter.node.findChildByType(PhpElementTypes.MODIFIER_LIST)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            styled(out, it.replace(Regex("\\s+"), " "), PhpHighlightingData.KEYWORD)
            out.append(' ')
        }
        if (PhaenonErasedTypes.typeLeaf(parameter) != null) {
            PhaenonErasedTypes.writtenType(parameter)?.let { styled(out, it, PhpHighlightingData.PRIMITIVE_TYPE_HINT) }
            out.append(' ')
        } else if (!parameter.declaredType.isEmpty) {
            // A native type, linked as PhpStorm links it; a variadic's declared type is its array.
            val declared = parameter.declaredType
            type(out, if (parameter.isVariadic) elements(declared) else declared)
            out.append(' ')
        }
        if (parameter.isPassByRef) out.append('&')
        if (parameter.isVariadic) out.append("...")
        styled(out, "$" + parameter.name, PhpHighlightingData.PARAMETER)
        parameter.defaultValue?.let { out.append(" = ").append(StringUtil.escapeXmlEntities(it.text.replace(Regex("\\s+"), " "))) }
    }

    /** A variadic's declared type, which PhpStorm holds as the array of it, as its elements. */
    private fun elements(declared: PhpType): PhpType {
        val elements = PhpType()
        for (member in declared.typesWithParametrisedParts) elements.add(if (PhpType.isPluralType(member)) PhpType.unpluralize(member) else member)
        return elements
    }

    /** A type PhpStorm holds, in Phaenon's spelling, classes linked as PhpStorm links them. */
    private fun type(out: StringBuilder, type: PhpType) {
        PhaenonTypePresentation.print(type, object : PhaenonTypePresentation.Sink {
            override fun klass(fqn: String) {
                out.append("<a href='psi_element://").append(StringUtil.escapeXmlEntities(fqn)).append("'>")
                    .append(StringUtil.escapeXmlEntities(fqn.substringAfterLast('\\'))).append("</a>")
            }

            override fun keyword(name: String) {
                styled(out, name, PhpHighlightingData.PRIMITIVE_TYPE_HINT)
            }

            override fun punctuation(text: String) {
                out.append(StringUtil.escapeXmlEntities(text))
            }
        })
    }

    private fun styled(out: StringBuilder, text: String, key: TextAttributesKey) {
        out.appendStyledSignatureFragment(StringUtil.escapeXmlEntities(text), key)
    }

    private companion object {
        /** How PhpStorm opens and closes the definition block of its popup. */
        const val DEFINITION_START = "<div class='definition'><pre>"
        const val DEFINITION_END = "</pre></div>"
    }
}
