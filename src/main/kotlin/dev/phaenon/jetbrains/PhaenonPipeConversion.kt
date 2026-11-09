package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.IntentionActionFilter
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.ArrayAccessExpression
import com.jetbrains.php.lang.psi.elements.BinaryExpression
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MemberReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.TernaryExpression
import com.jetbrains.php.lang.psi.elements.UnaryExpression

/**
 * "Convert nested function calls to a pipe expression" for `.phn` files, where it converts method
 * calls correctly: `$this->output->writeln(Style::line(sprintf('%s', $ns)))` becomes
 * `sprintf('%s', $ns) |> Style::line(...) |> $this->output->writeln(...)`.
 *
 * PhpStorm's own quick-fix for its "Nested function calls can be replaced with a pipe operator"
 * inspection (PhpStorm with PHP 8.5, 2026.2 for one) writes each one-argument call as its first PSI
 * child followed by `(...)`. For a function that child is its name; for a method call it is the
 * object or class the method is called on, so the fix turns the line above into
 * `… |> Style(...) |> $this->output(...)` — in `.php` files as much as in `.phn` files. In a `.phn`
 * file [PhaenonPipeQuickFixFilter] hides that fix and this intention takes its place; `.php` files
 * keep PhpStorm's behaviour.
 *
 * The chain is the one PhpStorm's inspection reports: calls nested as the only call among each
 * other's arguments, at least two of them turned into pipe stages. The innermost call's single
 * argument starts the pipe when it is not itself a call, as in PhpStorm. A stage whose call takes
 * only the piped value becomes a first-class callable (`trim(...)`, `Style::line(...)`,
 * `$this->output->writeln(...)`); a call with other arguments, a named or spread argument, or a
 * nullsafe call (`$a?->b(...)` is not a callable PHP can create) becomes `(fn($x) => …)` with
 * the piped value in its place, parenthesised as PHP 8.5 requires of an arrow function in a pipe. The pipe is parenthesised where an operator around it would bind
 * tighter, and the result is reformatted.
 *
 * Available only where the project's PHP language level has the pipe operator (8.5): an IDE
 * that does not know `|>` would read the result as a syntax error.
 */
class PhaenonPipeConversionIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "Convert nested function calls to a pipe expression"

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
        PhaenonFiles.isPhaenonFile(element.containingFile?.viewProvider?.virtualFile) && hasPipes(project) && chain(element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val calls = chain(element) ?: return
        val outer = calls.first()
        val text = pipe(calls)
        val parenthesised = if (needsParentheses(outer)) "($text)" else text
        val file = outer.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val start = outer.textRange.startOffset
        document.replaceString(start, outer.textRange.endOffset, parenthesised)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        CodeStyleManager.getInstance(project).reformatText(file, start, start + parenthesised.length)
        editor?.caretModel?.moveToOffset(start)
    }

    /** The calls from the outermost to the innermost, or null when they do not make a pipe of two stages or more. */
    private fun chain(element: PsiElement): List<FunctionReference>? {
        var call = PsiTreeUtil.getParentOfType(element, FunctionReference::class.java, false) ?: return null
        while (true) {
            val outer = outerCall(call) ?: break
            call = outer
        }
        val calls = arrayListOf(call)
        while (true) {
            val inner = calls.last().parameters.filterIsInstance<FunctionReference>().singleOrNull() ?: break
            calls.add(inner)
        }
        if (calls.size < 2) return null
        return calls.takeIf { stages(it) >= 2 }
    }

    /** The call [call] is the only call argument of. */
    private fun outerCall(call: FunctionReference): FunctionReference? {
        val list = call.parent as? ParameterList ?: return null
        val outer = list.parent as? FunctionReference ?: return null
        return outer.takeIf { list.parameters.count { it is FunctionReference } == 1 }
    }

    /** Whether the innermost call's argument starts the pipe (and the innermost call is a stage). */
    private fun innermostIsAStage(calls: List<FunctionReference>): Boolean =
        calls.last().parameters.let { it.size == 1 && it[0] !is FunctionReference && isPlain(it[0]) }

    private fun stages(calls: List<FunctionReference>): Int = if (innermostIsAStage(calls)) calls.size else calls.size - 1

    private fun pipe(calls: List<FunctionReference>): String {
        val inward = calls.reversed()
        val stages = if (innermostIsAStage(calls)) inward else inward.drop(1)
        var previous: PsiElement = if (innermostIsAStage(calls)) inward.first().parameters[0] else inward.first()
        val out = StringBuilder(previous.text)
        for (call in stages) {
            out.append(" |> ").append(stage(call, previous))
            previous = call
        }
        return out.toString()
    }

    /** [call] as a stage of a pipe whose value is [piped], one of its arguments. */
    private fun stage(call: FunctionReference, piped: PsiElement): String {
        val callee = callee(call)
        if (callee != null && call.parameters.size == 1 && isPlain(piped)) return "$callee(...)"
        val text = call.text
        var name = "\$x"
        var n = 1
        while (Regex(Regex.escape(name) + "\\b").containsMatchIn(text)) name = "\$x" + n++
        val from = piped.textRange.startOffset - call.textRange.startOffset
        val to = piped.textRange.endOffset - call.textRange.startOffset
        return "(fn($name) => " + text.substring(0, from) + name + text.substring(to) + ")"
    }

    /** What is called, as a first-class callable would name it: `trim`, `Style::line`, `$this->output->writeln`. */
    private fun callee(call: FunctionReference): String? {
        val open = call.node.findChildByType(PhpTokenTypes.chLPAREN) ?: return null
        val text = call.containingFile.viewProvider.contents.subSequence(call.textRange.startOffset, open.startOffset).toString().trim()
        if (text.isEmpty() || "?->" in text) return null
        val erased = PsiTreeUtil.findChildrenOfType(call, PsiElement::class.java).any { PhaenonTokenTypes.isErased(it) && it.textRange.endOffset <= open.startOffset }
        return text.takeIf { !erased }
    }

    /** An argument passed by position and not spread: one a first-class callable can receive. */
    private fun isPlain(argument: PsiElement): Boolean {
        val before = PhpPsiUtil.getPrevSiblingIgnoreWhitespace(argument, true)
        val type = PsiUtilCore.getElementType(before)
        return type !== PhpTokenTypes.opCOLON && type !== PhpTokenTypes.opVARIADIC
    }

    /** `|>` binds looser than arithmetic and concatenation, so a pipe inside such an expression needs parentheses. */
    private fun needsParentheses(call: FunctionReference): Boolean {
        val parent = call.parent
        return parent is BinaryExpression || parent is UnaryExpression || parent is TernaryExpression ||
            parent is ArrayAccessExpression || (parent is MemberReference && parent.firstChild === call)
    }

    private fun hasPipes(project: Project): Boolean {
        val version = PhpProjectConfigurationFacade.getInstance(project).languageLevel.versionString.split('.').map { it.toIntOrNull() ?: 0 }
        val major = version.getOrElse(0) { 0 }
        return major > 8 || major == 8 && version.getOrElse(1) { 0 } >= 5
    }
}

/**
 * Hides PhpStorm's "Convert nested function calls to a pipe expression" quick-fix in `.phn` files,
 * where [PhaenonPipeConversionIntention] does the conversion right. The fix is told by its class
 * name, a string: the class is not public (package-private in the PHP plugin, 2026.1 to 2026.3).
 */
class PhaenonPipeQuickFixFilter : IntentionActionFilter {

    override fun accept(intentionAction: IntentionAction, file: PsiFile?): Boolean {
        if (file == null || !PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return true
        val fix: Any = QuickFixWrapper.unwrap(intentionAction) ?: IntentionActionDelegate.unwrap(intentionAction)
        return fix.javaClass.name != PHPSTORM_FIX
    }

    private companion object {
        const val PHPSTORM_FIX = "com.jetbrains.php.lang.inspections.PhpPipeOperatorCanBeUsedInspection\$PhpConvertNestedFunctionCallsToPipeExpressionQuickFix"
    }
}
