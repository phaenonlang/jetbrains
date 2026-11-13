package dev.phaenon.jetbrains

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.ConstantReference
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.PhpCallableFunction

/**
 * A PHP 8.6 partial application as the editor sees it (design/php-versions.md §5): a call in a
 * `.phn` file with a placeholder among its arguments — `?` for one argument, `name: ?` for a named
 * one, a trailing `...` for all that remain. Up to 2026.2 [PhaenonLexer] hands each `?` to
 * PhpStorm's parser as an identifier, so the call is ordinary PSI with a constant named `?` for an
 * argument, and the trailing `...` is PhpStorm's own spread without an operand. From 2026.3 on
 * PhpStorm's parser makes the placeholders itself ([phpStormParsesPlaceholders]): the call is then
 * PhpStorm's partial application, a [PhpCallableFunction] (the element of a first-class callable)
 * whose placeholders are nodes of PhpStorm's ([isPhpStormsPlaceholder]), and PhpStorm types it as
 * the closure it creates.
 *
 * The shape is what the closure's parameters are made from: one flag per positional argument
 * (placeholder or bound), the named arguments in the order written, and whether `...` ends the
 * list. It travels inside a type signature, so it has a text form ([encode], [decode]).
 */
class PhaenonPartialApplication private constructor(
    private val positional: List<Boolean>,
    private val named: List<Pair<String, Boolean>>,
    private val variadic: Boolean,
) {

    /** `?b;name=?,other=b;v`: positional flags, named arguments, the variadic marker. */
    fun encode(): String =
        positional.joinToString("") { if (it) "?" else "b" } + ";" +
            named.joinToString(",") { (name, placeholder) -> name + "=" + if (placeholder) "?" else "b" } + ";" +
            if (variadic) "v" else ""

    /**
     * The parameters of the callee the closure takes, in PHP's order: those positional
     * placeholders stand for, then named ones in the order written, then — for `...` — every
     * parameter no argument binds. Null when the placeholders do not fit the callee (too many
     * arguments, an unknown name), which the compiler reports.
     */
    fun closureParameters(parameters: Array<Parameter>): List<Parameter>? {
        val result = ArrayList<Parameter>()
        val bound = HashSet<String>()
        positional.forEachIndexed { i, placeholder ->
            val parameter = parameters.getOrNull(i) ?: parameters.lastOrNull()?.takeIf { it.isVariadic } ?: return null
            if (placeholder) result.add(parameter)
            bound.add(parameter.name)
        }
        for ((name, placeholder) in named) {
            val parameter = parameters.firstOrNull { it.name == name } ?: return null
            if (placeholder) result.add(parameter)
            bound.add(name)
        }
        if (variadic) parameters.drop(positional.size).filterTo(result) { it.name !in bound }
        return result
    }

    companion object {
        /** The text of a placeholder argument. */
        const val PLACEHOLDER = "?"

        /**
         * The node PhpStorm's parser makes of a placeholder from 2026.3 on,
         * `PhpElementTypes.PARTIAL_ARGUMENT_PLACEHOLDER`: looked up by name, as the plugin is built
         * against a PhpStorm that has none, and null in such a PhpStorm.
         */
        private val PHPSTORM_PLACEHOLDER: IElementType? = try {
            PhpElementTypes::class.java.getField("PARTIAL_ARGUMENT_PLACEHOLDER").get(null) as? IElementType
        } catch (e: ReflectiveOperationException) {
            null
        }

        /**
         * Whether this PhpStorm's parser makes placeholders itself (2026.3 on): then a `.phn`'s
         * `?` reaches it as PHP's own, and a partial application is PhpStorm's.
         */
        @JvmStatic
        val phpStormParsesPlaceholders: Boolean get() = PHPSTORM_PLACEHOLDER != null

        /** Whether [element] is a placeholder PhpStorm's own parser made, `...` or `?` in a node of its own (2026.3 on). */
        @JvmStatic
        fun isPhpStormsPlaceholder(element: PsiElement?): Boolean =
            PHPSTORM_PLACEHOLDER != null && PsiUtilCore.getElementType(element) === PHPSTORM_PLACEHOLDER

        /** Whether [element] is a `?` placeholder of a `.phn` file: the constant [PhaenonLexer] made of it (up to 2026.2), or PhpStorm's own. */
        @JvmStatic
        fun isPlaceholder(element: PsiElement?): Boolean =
            element is ConstantReference && element.text == PLACEHOLDER && PhaenonErasedTypes.isPhaenon(element) ||
                element != null && isPhpStormsPlaceholder(element) && element.textMatches(PLACEHOLDER)

        /**
         * Whether [leaf] is the trailing `...` of a partial application, `f(1, ...)`: a spread
         * with nothing after it, in an argument list that has an argument before it. The sole
         * `f(...)` is PHP 8.1's first-class callable, which PhpStorm parses as such.
         */
        @JvmStatic
        fun isVariadicPlaceholder(leaf: PsiElement?): Boolean {
            if (leaf == null || PsiUtilCore.getElementType(leaf) !== PhpTokenTypes.opVARIADIC) return false
            if (!PhaenonErasedTypes.isPhaenon(leaf)) return false
            val next = PsiTreeUtil.nextVisibleLeaf(leaf) ?: return false
            if (PsiUtilCore.getElementType(next) !== PhpTokenTypes.chRPAREN && PsiUtilCore.getElementType(next) !== PhpTokenTypes.opCOMMA) return false
            val previous = PsiTreeUtil.prevVisibleLeaf(leaf) ?: return false
            return PsiUtilCore.getElementType(previous) === PhpTokenTypes.opCOMMA
        }

        /**
         * The shape of [call] — a call, or PhpStorm's own partial application ([PhpCallableFunction],
         * 2026.3 on) — when it is a partial application of a `.phn` file, null otherwise.
         */
        @JvmStatic
        fun of(call: PsiElement): PhaenonPartialApplication? {
            if (call !is FunctionReference && call !is PhpCallableFunction) return null
            if (!PhaenonErasedTypes.isPhaenon(call)) return null
            val list = PsiTreeUtil.getChildOfType(call, ParameterList::class.java) ?: return null
            val positional = ArrayList<Boolean>()
            val named = ArrayList<Pair<String, Boolean>>()
            var variadic = false
            var any = false
            var name: String? = null
            var child: PsiElement? = list.firstChild
            while (child != null) {
                val type = PsiUtilCore.getElementType(child)
                when {
                    child is PsiWhiteSpace || child is PsiComment || type === PhpTokenTypes.opCOMMA -> Unit
                    type === PhpTokenTypes.opCOLON -> Unit
                    type === PhpTokenTypes.opVARIADIC -> {
                        if (!isVariadicPlaceholder(child)) return null // unpacking: no placeholders with it
                        variadic = true
                        any = true
                    }
                    isPhpStormsPlaceholder(child) && child.textMatches("...") -> {
                        variadic = true
                        any = true
                    }
                    // A named argument's name: an identifier, or a reserved word, before its colon.
                    child.firstChild == null && nextIsColon(child) -> name = child.text
                    else -> {
                        val placeholder = isPlaceholder(child)
                        any = any || placeholder
                        if (name != null) named.add(name to placeholder) else positional.add(placeholder)
                        name = null
                    }
                }
                child = child.nextSibling
            }
            return if (any) PhaenonPartialApplication(positional, named, variadic) else null
        }

        /**
         * Whether [call] is a partial application whose last argument is the trailing `...`, which
         * stands for every parameter its other arguments leave (PHP 8.6): the call then lacks no
         * argument, whatever it calls. A `...` before another argument is no such call: PHP
         * rejects it ("Variadic placeholder must be last").
         */
        @JvmStatic
        fun takesTheRest(call: PsiElement): Boolean {
            if (of(call) == null) return false
            val list = PsiTreeUtil.getChildOfType(call, ParameterList::class.java) ?: return false
            var last: PsiElement? = list.lastChild
            while (last is PsiWhiteSpace || last is PsiComment || last != null && PsiUtilCore.getElementType(last) === PhpTokenTypes.opCOMMA) {
                last = last.prevSibling
            }
            return isVariadicPlaceholder(last) || last != null && isPhpStormsPlaceholder(last) && last.textMatches("...")
        }

        private fun nextIsColon(element: PsiElement): Boolean {
            var next = element.nextSibling
            while (next is PsiWhiteSpace || next is PsiComment) next = next.nextSibling
            return next != null && PsiUtilCore.getElementType(next) === PhpTokenTypes.opCOLON
        }

        /** The shape [encode] wrote; null when the text is not one. */
        @JvmStatic
        fun decode(text: String): PhaenonPartialApplication? {
            val parts = text.split(';')
            if (parts.size != 3) return null
            val positional = parts[0].map { it == '?' }
            val named = parts[1].split(',').filter { it.isNotEmpty() }.map { it.substringBefore('=') to (it.substringAfter('=') == "?") }
            return PhaenonPartialApplication(positional, named, parts[2] == "v")
        }
    }
}
