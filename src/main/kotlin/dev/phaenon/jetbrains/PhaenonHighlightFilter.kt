package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
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
 * `fun` declares a public method (design/functions.md §1), a class constant without a visibility
 * is public, and so is a property with an asymmetric visibility and no main one: the PSR-12
 * "missing visibility" finding does not apply, and the compiler writes the `public` out.
 * A closure in a constant expression — an attribute argument, a constant, a default — is lowered
 * to the `static function` PHP 8.5 accepts there (§2), so it is not an invalid constant expression.
 *
 * PhpStorm's "Declaration must be compatible with …" and "Return type declaration must be
 * compatible with …" compare the types declared in the PSI, and an erased type declares none: to
 * PhpStorm the interface's `save(T $aggregate)` takes `mixed`, and every typed implementation
 * narrows it. On an override where either side has an erased type, in a `.phn` or in a `.php`
 * implementing a Phaenon interface, PhpStorm's finding goes and [PhaenonHierarchyInspection]
 * judges the pair with the types the compiler writes; it reports the real incompatibilities.
 *
 * PhpStorm encodes a closure literal's or a first-class callable's variadic parameter as an array
 * (`fn (string ...$args)` is `\Closure<string[]>` to it), and that encoding, inferred from an
 * assignment, sits beside the signature a `Closure(string...): …` declaration gives the property.
 * When PhpStorm checks a call against its own encoding ("Parameter type 'string' is not compatible
 * with 'string[]'"), the finding goes where the argument fits the declared Phaenon signature.
 *
 * A docblock type naming a type parameter in scope, `@var T $x`, is "Undefined class 'T'" to
 * PhpStorm, which has no `@template` to read it from; the name is declared in the erased list.
 *
 * "Other declarations of class 'X' exist at X.php" is PhpStorm seeing the `.php` the compiler
 * wrote from the class's own `.phn`: not a conflict, the same class twice. The finding stays when
 * some other declaration is hand-written.
 */
class PhaenonHighlightFilter : HighlightInfoFilter {

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null) return true
        val description = info.description ?: return true
        if (INCOMPATIBLE_WITH_PARENT.any { description.startsWith(it) }) return !overrideIsPhaenons(info, file)
        if (!PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return true
        if (description.startsWith(OTHER_DECLARATIONS_PREFIX)) return otherDeclarationIsHandWritten(info, file)
        if (description == PSR_MISSING_VISIBILITY) return !visibilityIsImplied(info, file)
        if (description in INVALID_CONSTANT_EXPRESSION) return !closureInConstantExpression(info, file)
        if (UNDEFINED_CLASS.let { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) {
            val leaf = file.findElementAt(info.startOffset) ?: return true
            return leaf.text !in PhaenonErasedTypes.typeParametersInScope(leaf)
        }
        if (UNUSED_IMPORT.any { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) {
            return !importIsUsedInAnErasedType(info, file)
        }
        if (description.startsWith(CLOSURE_ARGUMENT.first) && description.endsWith(CLOSURE_ARGUMENT.second)) return !argumentFitsPhaenonClosure(info, file)
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

    /**
     * PhpStorm's hierarchy finding on a method whose override [PhaenonHierarchyInspection] judges:
     * dropped, unless the finding is that inspection's own.
     */
    private fun overrideIsPhaenons(info: HighlightInfo, file: PsiFile): Boolean {
        if (info.problemGroup === PhaenonHierarchyInspection.PROBLEMS) return false
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val method = PsiTreeUtil.getParentOfType(leaf, Method::class.java) ?: return false
        return PhaenonHierarchyInspection.owns(method)
    }

    /**
     * Whether the argument PhpStorm flagged fits the parameter a Phaenon closure type declares for
     * it: `($this->kubectl)('get')` with `Closure(string...): (int, string) $kubectl`.
     */
    private fun argumentFitsPhaenonClosure(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val arguments = PsiTreeUtil.getParentOfType(leaf, ParameterList::class.java) ?: return false
        val call = arguments.parent as? FunctionReference ?: return false
        val index = arguments.parameters.indexOfFirst { PsiTreeUtil.isAncestor(it, leaf, false) }
        if (index < 0) return false
        val argument = arguments.parameters[index] as? PhpTypedElement ?: return false
        val declaration = closureDeclaration(call.firstPsiChild) ?: return false
        val closures = PhaenonType.parse(PhaenonErasedTypes.beforeName(declaration)).closures()
        if (closures.isEmpty()) return false
        val project = file.project
        val given = argument.type.global(project)
        val text = PhaenonTypeText(declaration, PhaenonErasedTypes.typeParameterBounds(declaration))
        return closures.any { closure ->
            val variadic = closure.parameters.indexOfFirst { it.variadic }
            val parameter = closure.parameters.getOrNull(index) ?: closure.parameters.getOrNull(variadic).takeIf { variadic in 0..index }
            // The convertibility PhpStorm's own closure-signature check applies, strict as a Phaenon file is.
            parameter != null && PhpParamsInspection.isCallTypeConvertibleFromDeclaredType(text.type(parameter.type).global(project), given, PhpIndex.getInstance(project))
        }
    }

    /** The property or parameter a called expression reads: `($this->kubectl)`, `$kubectl`. */
    private fun closureDeclaration(callee: PsiElement?): PhpNamedElement? {
        var expression = callee
        while (expression is ParenthesizedExpression) expression = expression.argument
        val reference = expression as? PhpReference ?: return null
        val target = reference.resolve()
        return (target as? Field ?: target as? Parameter)?.takeIf { PhaenonErasedTypes.isPhaenon(it) }
    }

    /** The closure types a declared type spells: the type itself, or the members of a union. */
    private fun PhaenonType?.closures(): List<PhaenonType.Closure> = when (this) {
        is PhaenonType.Closure -> listOf(this)
        is PhaenonType.Union -> members.filterIsInstance<PhaenonType.Closure>()
        else -> emptyList()
    }

    /** A `fun` method, a class constant, or a property or parameter with an asymmetric visibility and no main one, is public (design/functions.md §1). */
    private fun visibilityIsImplied(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val declaration: PsiElement = PsiTreeUtil.getParentOfType(leaf, false, Method::class.java, Field::class.java, Parameter::class.java) ?: return false
        if (declaration is Method) return declaration.node.findChildByType(PhpTokenTypes.kwFUNCTION)?.text == "fun"
        if (declaration is Field && declaration.isConstant) return true
        return declaration.node.findChildByType(PhpElementTypes.MODIFIER_LIST)?.text?.contains("(set)") == true
    }

    /** A closure in an attribute argument, a constant or a default compiles to the `static function` PHP accepts there (design/functions.md §2). */
    private fun closureInConstantExpression(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val function = PsiTreeUtil.getParentOfType(leaf, Function::class.java, false) ?: return false
        return function.isClosure
    }

    /** `use App\Event\DomainEvent;` is used by `list<DomainEvent>`, which PhpStorm cannot see. */
    private fun importIsUsedInAnErasedType(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val use = PsiTreeUtil.getParentOfType(leaf, PhpUse::class.java, false) ?: return false
        return PhaenonErasedNames.isUsedByErasedConstruct(use)
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
        val PSR_MISSING_VISIBILITY: String = PhpBundle.message("inspection.message.psr.missing.visibility.definition")
        /** What PhpStorm's annotator says of a closure where PHP wants a constant expression, position by position. */
        val INVALID_CONSTANT_EXPRESSION: Set<String> = setOf(
            "constant.expression.contains.invalid.operations",
            "annotation.expression.is.not.allowed.as.class.constant.value",
            "expression.is.not.allowed.as.constant.value",
            "expression.is.not.allowed.as.parameter.default.value",
            "expression.is.not.allowed.as.field.default.value",
            "expression.is.not.allowed.as.static.initializer",
        ).map { PhpBundle.message(it) }.toSet()

        /** "Return type declaration must be compatible with X : Y", "Declaration must be compatible with X", up to what is filled in. */
        val INCOMPATIBLE_WITH_PARENT: List<String> = listOf(
            PhpBundle.message("inspection.message.return.type.declaration.must.be.compatible.with", "\u0000", "\u0000"),
            PhpBundle.message("declaration.must.be.compatible.with.0", "\u0000"),
            PhpBundle.message("declaration.must.be.compatible.with.super"),
            PhpBundle.message("return.type.declaration.must.be.compatible.with.super"),
        ).map { it.substringBefore('\u0000') }

        /** What PhpStorm says of an argument its closure-signature check rejects, split around the two types. */
        val CLOSURE_ARGUMENT: Pair<String, String> =
            PhpBundle.message("parameter.type.is.not.compatible.with.declaration", "\u0000", "\u0000").let { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }

        /** "Undefined class '#ref'", split around the name: `T` in a docblock type is a type parameter, not a class. */
        val UNDEFINED_CLASS: Pair<String, String> =
            PhpBundle.message("inspection.php.undefined.class.description2").let { it.substringBefore("#ref") to it.substringAfter("#ref") }

        /** "Import '#ref' is never used" / "is unnecessary", split around the name the inspection fills in. */
        val UNUSED_IMPORT: List<Pair<String, String>> = listOf("inspection.php.unused.alias.description", "inspection.php.unused.alias.description2")
            .map { PhpBundle.message(it) }
            .map { it.substringBefore("#ref") to it.substringAfter("#ref") }

        /** The message up to the class name, which the inspection fills in for `#ref`. */
        val OTHER_DECLARATIONS_PREFIX: String =
            PhpBundle.message("inspection.php.undefined.class.other.declaration.exists", "").substringBefore("#ref")
    }
}
