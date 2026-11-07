package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClassFieldsList
import com.jetbrains.php.lang.psi.elements.ConstantReference
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.ParenthesizedExpression
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesList
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpEnumCase
import com.jetbrains.php.lang.psi.elements.PhpExpression
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.jetbrains.php.lang.psi.elements.Variable

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
 * to the `static function` PHP 8.5 accepts there (§2), so it is not an invalid constant expression,
 * nor a closure-valued default that "must be declared static" (PhpStorm 2025.3).
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
 *
 * PHP 8.6 (design/php-versions.md §4), which no PhpStorm knows, 2025.3 through 2026.3 — their
 * language levels stop at 8.5:
 *
 * - a partial application's `?` is a constant to PhpStorm ([PhaenonLexer]), "undefined", and its
 *   trailing `...` "a spread without an expression"; in a constant expression it is "not allowed",
 *   and as a pipe stage "not a valid callable" (PhpStorm types it as the callee's result). The
 *   compiler emits it as written for 8.6 and lowers it for 8.5, so these go on either target;
 * - `#[\Override]` on a class constant or an enum case "cannot be applied" by the attribute's
 *   targets in PhpStorm's stubs; PHP 8.6 allows it, and for 8.5 the compiler checks it and drops
 *   the attribute, so this goes on either target too;
 * - "readonly property cannot have default value", "Enum may not include '__debugInfo'" and
 *   "Can't use temporary expression in write context" on `CONFIG->prop = $v` go when the file's
 *   target ([PhaenonTarget]) is 8.6. On an 8.5 target they stay: the compiler refuses them there,
 *   "… needs PHP 8.6 (target 8.5)".
 *
 * A `concurrently` block used as a value and a `spawn` are closures to PhpStorm ([PhaenonBlocks]),
 * with a scope of their own and no `use` clause; the compiler writes that clause. So a variable of
 * the enclosing function read in a block is not "undefined", nor one a block assigns and the
 * function reads after it, and a parameter or variable used only on the other side of a block
 * boundary is not "unused". What PhpStorm says of the closure itself it says at the keyword
 * (`spawn $mailer->send($m);` is an expression whose result "is not used"): that goes too.
 *
 * A function's `throws A|B` clause is what its compiled `@throws` tag says, but PhpStorm's exception
 * analysis reads only docblocks: "Unhandled X" (or "Unhandled exceptions") goes where the clauses
 * in effect name X or a parent of it, as does "PHPDoc comment doesn't contain all the necessary
 * @throws tags" where they name what the docblock lacks ([PhaenonThrows] says which clauses those are).
 */
class PhaenonHighlightFilter : HighlightInfoFilter {

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null) return true
        val description = info.description ?: return true
        if (INCOMPATIBLE_WITH_PARENT.any { description.startsWith(it) }) return !overrideIsPhaenons(info, file)
        if (!PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) return true
        if (description.startsWith(OTHER_DECLARATIONS_PREFIX)) return otherDeclarationIsHandWritten(info, file)
        if (description == PSR_MISSING_VISIBILITY) return !visibilityIsImplied(info, file)
        if (description in INVALID_CONSTANT_EXPRESSION) return !closureInConstantExpression(info, file) && !partialApplicationAt(info, file)
        if (UNDEFINED_CONSTANT.any { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) {
            return !PhaenonPartialApplication.isPlaceholder(file.findElementAt(info.startOffset)?.parent)
        }
        if (description == SPREAD_WITHOUT_EXPRESSION) return !PhaenonPartialApplication.isVariadicPlaceholder(file.findElementAt(info.startOffset))
        if (NOT_A_VALID_CALLABLE.any { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) return !partialApplicationAt(info, file)
        if (description.startsWith(ATTRIBUTE_TARGET.first) && description.endsWith(ATTRIBUTE_TARGET.second)) return !overrideOnConstant(info, file)
        if (description in PHP86_ONLY) return !php86Construct(info, file, description)
        if (PhaenonBlocks.isKeywordAt(file, info.startOffset)) return false
        if (description == UNHANDLED_SEVERAL || description.startsWith(UNHANDLED.first) && description.endsWith(UNHANDLED.second)) {
            return !PhaenonThrows.answersUnhandled(file.findElementAt(info.startOffset) ?: return true)
        }
        if (description == MISSING_THROWS_TAGS) {
            // The finding marks the docblock.
            val leaf = file.findElementAt(info.startOffset)
            val function = PsiTreeUtil.getParentOfType(leaf, PhpDocComment::class.java, false)?.owner as? Function
                ?: PsiTreeUtil.getParentOfType(leaf, Function::class.java, false) ?: return true
            return !PhaenonThrows.answersMissingTags(function)
        }
        if (UNDEFINED_VARIABLE.any { (prefix, suffix) -> description.startsWith(prefix) && description.endsWith(suffix) }) {
            val variable = PsiTreeUtil.getParentOfType(file.findElementAt(info.startOffset), Variable::class.java, false) ?: return true
            return !PhaenonBlocks.isKnownAcrossBlocks(variable)
        }
        if (UNUSED_VARIABLE.any { description.startsWith(it) }) {
            val leaf = file.findElementAt(info.startOffset) ?: return true
            val declaration: PhpNamedElement = PsiTreeUtil.getParentOfType(leaf, Variable::class.java, Parameter::class.java) ?: return true
            val name = declaration.name ?: return true
            return !PhaenonBlocks.isUsedAcrossBlocks(declaration, name)
        }
        if (description.startsWith(INCOMPATIBLE_RETURN.first) && description.endsWith(INCOMPATIBLE_RETURN.second)) return !returnsABlock(info, file)
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
     * it: `($this->request)('get')` with `Closure(string...): (int, string) $request`.
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

    /** The property or parameter a called expression reads: `($this->request)`, `$request`. */
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

    /** `return concurrently { … };`: the block is its body's value, which PhpStorm holds beside a `\Closure`. */
    private fun returnsABlock(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val statement = PsiTreeUtil.getParentOfType(leaf, PhpReturn::class.java, false) ?: return false
        val value = statement.argument as? PhpExpression ?: return false
        return PhaenonBlocks.kind(value.firstChild as? Function) == PhaenonBlocks.Kind.CONCURRENTLY
    }

    /** A partial application where the finding starts: `f(?, 1)` as a constant or a pipe stage. */
    private fun partialApplicationAt(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val call = PsiTreeUtil.getParentOfType(leaf, FunctionReference::class.java, false) ?: return false
        return PhaenonPartialApplication.of(call) != null
    }

    /** `#[\Override]` on a class constant or an enum case, which PHP 8.6 allows and the compiler drops for 8.5. */
    private fun overrideOnConstant(info: HighlightInfo, file: PsiFile): Boolean {
        val leaf = file.findElementAt(info.startOffset) ?: return false
        val attribute = PsiTreeUtil.getParentOfType(leaf, PhpAttribute::class.java, false) ?: return false
        if (attribute.fqn != "\\Override") return false
        val owner = PsiTreeUtil.getParentOfType(attribute, PhpAttributesList::class.java)?.parent ?: return false
        return owner is PhpEnumCase || owner is Field && owner.isConstant ||
            owner is PhpClassFieldsList && owner.fields.any { it.isConstant }
    }

    /** The PHP 8.6 construct a finding is about, on an 8.6 target: a readonly default, an enum's `__debugInfo()`, a write through a constant. */
    private fun php86Construct(info: HighlightInfo, file: PsiFile, description: String): Boolean {
        if (!PhaenonTarget.isPhp86(file)) return false
        val leaf = file.findElementAt(info.startOffset) ?: return false
        return when (description) {
            READONLY_DEFAULT -> PsiTreeUtil.getParentOfType(leaf, Field::class.java) != null
            ENUM_DEBUG_INFO -> PsiTreeUtil.getParentOfType(leaf, Method::class.java)?.containingClass?.isEnum == true
            TEMPORARY_IN_WRITE -> {
                val write = PsiTreeUtil.getParentOfType(leaf, FieldReference::class.java, false) ?: return false
                write.classReference is ConstantReference
            }
            else -> false
        }
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
            "closures.as.default.values.must.be.static", // 2025.3: a non-static closure as a default
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

        /** "Unhandled {0}", around the exception's name. */
        val UNHANDLED: Pair<String, String> = PhpBundle.message("unhandled.0", "\u0000").let { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }
        val UNHANDLED_SEVERAL: String = PhpBundle.message("unhandled.exceptions")
        val MISSING_THROWS_TAGS: String = PhpBundle.message("inspection.doc.throws.problem")

        /** "Undefined variable '#ref'", "Variable '#ref' is probably undefined". */
        val UNDEFINED_VARIABLE: List<Pair<String, String>> = listOf("inspection.undefined.variable.problem1", "inspection.undefined.variable.problem2")
            .map { PhpBundle.message(it) }
            .map { it.substringBefore("#ref") to it.substringAfter("#ref") }

        /** "Unused local variable 'x'. …", "Unused parameter 'x'. …", up to the name. */
        val UNUSED_VARIABLE: List<String> = listOf("inspection.unused.local.variable.problem", "inspection.unused.parameter.problem")
            .map { PhpBundle.message(it, "\u0000", "").substringBefore('\u0000') }

        /** "Return value is expected to be '{0}', '{1}' returned", around what is filled in. */
        val INCOMPATIBLE_RETURN: Pair<String, String> =
            PhpBundle.message("inspection.incompatible.return.type", "\u0000", "\u0000").let { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }

        /** "Undefined constant '#ref'", "Constant '#ref' is probably undefined": a placeholder `?` to PhpStorm. */
        val UNDEFINED_CONSTANT: List<Pair<String, String>> = listOf("inspection.undefined.constant.problem1", "inspection.undefined.constant.problem2")
            .map { PhpBundle.message(it) }
            .map { it.substringBefore("#ref") to it.substringAfter("#ref") }

        val SPREAD_WITHOUT_EXPRESSION: String = PhpBundle.message("expected.expression.after.spread.operator")

        /** What PhpStorm 2026.2 says of a pipe stage it does not take for a callable; absent from 2025.1, which has no pipe. */
        val NOT_A_VALID_CALLABLE: List<Pair<String, String>> = listOf(
            "php.annotator.pipe.expression.value.of.type.is.not.valid.callable",
            "php.annotator.pipe.expression.not.valid.callable",
        ).mapNotNull { phpMessage(it, "\u0000") }.map { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }

        /** "Attribute cannot be applied to a {0} because it does not contain the '{1}' flag", around what is filled in. */
        val ATTRIBUTE_TARGET: Pair<String, String> =
            PhpBundle.message("inspection.message.attribute.can.t.be.applied.to.because.it.doest.contains", "\u0000", "\u0000")
                .let { it.substringBefore('\u0000') to it.substringAfterLast('\u0000') }

        /**
         * Looked up by key, null where this PhpStorm has none: a key goes when a PhpStorm learns
         * the construct (2026.3 dropped the readonly default's), and a finding it no longer reports
         * needs no filtering. [PhpBundle.message] on a missing key would fail this object's
         * initialisation and with it every rule above.
         */
        val READONLY_DEFAULT: String? = phpMessage("readonly.property.cannot.have.default.value")
        val ENUM_DEBUG_INFO: String? = phpMessage("enum.may.not.include.0", "__debugInfo")
        val TEMPORARY_IN_WRITE: String? = phpMessage("cant.use.temporary.expression.in.write.context")
        val PHP86_ONLY: Set<String> = setOfNotNull(READONLY_DEFAULT, ENUM_DEBUG_INFO, TEMPORARY_IN_WRITE)

        /** A message of PhpStorm's bundle, or null when this PhpStorm does not have the key. */
        private fun phpMessage(key: String, vararg arguments: Any): String? {
            val bundle = try {
                ResourceBundle.getBundle("messages.PhpBundle", Locale.ROOT, PhpBundle::class.java.classLoader)
            } catch (e: MissingResourceException) {
                return null
            }
            if (!bundle.containsKey(key)) return null
            return PhpBundle.message(key, *arguments)
        }

        /** The message up to the class name, which the inspection fills in for `#ref`. */
        val OTHER_DECLARATIONS_PREFIX: String =
            PhpBundle.message("inspection.php.undefined.class.other.declaration.exists", "").substringBefore("#ref")
    }
}
