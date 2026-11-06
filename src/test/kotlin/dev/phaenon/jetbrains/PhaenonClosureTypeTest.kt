package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassInspection
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.psalm.lang.inspections.PsalmAdvanceCallableParamsInspection
import com.jetbrains.php.lang.inspections.PhpUnusedAliasInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingFieldTypeInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingParamTypeInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingReturnTypeInspection
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclarationOwner
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.Variable
import com.jetbrains.php.lang.psi.elements.impl.PhpExpressionImpl
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import java.io.File

/**
 * `Closure(A, B=, C...): R` is `\Closure` whatever the file's namespace and imports: the compiler
 * reads `Closure(` as a type form and writes `\Closure`. PhpStorm would resolve a bare `Closure`
 * in the file's namespace, so the plugin erases the bare spelling with its signature and gives the
 * declaration `\Closure` back; `\Closure(…)` keeps its name. A `Closure` without a signature is
 * PHP's and resolves as in PHP. The fixtures sit in a namespace, `Acme\Shop\Payments`, where a
 * bare `Closure` read as PHP is "Undefined class 'Closure'".
 */
class PhaenonClosureTypeTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyFileToProject("closure/stubs/Closure.php", "stubs/Closure.php")
    }

    fun testABareClosureTypeIsNoUndefinedClassInANamespace() {
        myFixture.enableInspections(
            PhpUndefinedClassInspection::class.java,
            PhpMissingFieldTypeInspection::class.java,
            PhpMissingParamTypeInspection::class.java,
            PhpMissingReturnTypeInspection::class.java,
        )
        myFixture.configureByFiles("closure/Gateway.phn")

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
            .map { "${it.description} '${it.text}' @${myFixture.editor.document.getLineNumber(it.startOffset) + 1}" }

        assertEmpty(findings)
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
    }

    fun testABareClosureTypeIsTheClosureClass() {
        myFixture.configureByFiles("closure/Gateway.phn")

        val types = types(myFixture.file) { (it as PhpTypedElement).type }

        // The signature travels as PhpStorm encodes a docblock `\Closure(…): …`; a tuple result is
        // the plugin's tuple, the union of its element arrays.
        val request = closure(listOf("\\string"), "\\int[]", "\\string[]")
        assertEquals("promoted parameter", listOf("\\Closure", closure(listOf("\\string"), "\\bool")), types["__construct(\$accept)"])
        assertEquals("nullable parameter", listOf("\\Closure", request, "\\null"), types["__construct(\$request)"])
        assertEquals("parameter", listOf("\\Closure", request), types["line(\$request)"])
        assertEquals("union member", listOf("\\Closure", closure(listOf("\\int"), "\\bool"), "\\int"), types["either(\$test)"])
        assertEquals("return type without a body", listOf("\\Closure", request), types["transport()"])
        assertEquals("the promoted property, read to call it", listOf("\\Closure", closure(listOf("\\string"), "\\bool")), types["->accept"])
        // An erased declaration has no type of its own to PhpStorm, which adds what it infers from
        // the assignments and the body (README § Limitations): the closure's own `\Closure<…>`,
        // and `mixed` through `$request ?? …` on a parameter it sees as untyped.
        for (key in listOf("\$request", "->request", "request()")) {
            val type = types.getValue(key)
            assertTrue("$key: $type", "\\Closure" in type && type.all { it.startsWith("\\Closure") || it == "\\mixed" })
        }
    }

    fun testTheQualifiedSpellingDeclaresTheSameTypes() {
        val bare = types(myFixture.configureByFiles("closure/Gateway.phn").single(), ::declaredType)
        val qualified = types(myFixture.configureByText("Qualified.phn", qualifiedCopy()), ::declaredType)

        assertEquals(bare, qualified)
    }

    fun testABareClosureTypeNavigatesToPhpsClosureBesideANamespaceClassNamedClosure() {
        myFixture.configureByFiles("closure/Gateway.phn", "closure/Decoy.phn")
        val bareTargets = closureTargets()
        myFixture.configureByText("Qualified.phn", qualifiedCopy())
        val qualifiedTargets = closureTargets()

        assertEquals(List(7) { "\\Closure" to "/src/stubs/Closure.php" }, bareTargets)
        assertEquals(bareTargets, qualifiedTargets)
        val decoy = PhpIndex.getInstance(project).getClassesByFQN("\\Acme\\Shop\\Payments\\Closure").single()
        val uses = myFixture.findUsages(decoy).filterNot { it.isNonCodeUsage }.map { it.file?.name to it.element?.text }
        assertEmpty("the bare spelling is no use of the namespace's Closure", uses)
    }

    fun testABareClosureTypeCompletesTheClosureMethods() {
        myFixture.configureByFiles("closure/Completing.phn")

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings!!, "bindTo", "call")
    }

    fun testAnImportOfClosureStaysUsed() {
        // The bare spelling needs no import, but one written stays used: `phn check` (P2013) counts
        // the name in the type as a use, and so did PhpStorm while the name was a PHP reference.
        myFixture.enableInspections(PhpUnusedAliasInspection::class.java, PhpUndefinedClassInspection::class.java)
        myFixture.configureByFiles("closure/Imported.phn")

        val findings = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }.map { "${it.description} '${it.text}'" }

        assertEmpty(findings)
    }

    fun testAClosureWithoutASignatureIsPhps() {
        myFixture.enableInspections(PhpUndefinedClassInspection::class.java)
        myFixture.configureByFiles("closure/Plain.phn")

        val undefined = myFixture.doHighlighting().filter { it.description.orEmpty().startsWith("Undefined class") }.map { it.text }
        val parameter = PsiTreeUtil.findChildOfType(myFixture.file, Parameter::class.java)!!

        assertEquals(listOf("Closure"), undefined)
        assertEquals(listOf("\\Acme\\Shop\\Payments\\Closure"), parameter.type.global(project).types.sorted())
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) })
    }

    fun testAClosureTypeCarriesTheSignaturePhpStormReadsFromItsDocblockSpelling() {
        // `\Closure(string, int=): void` in a docblock is what the compiler writes for the Phaenon
        // type; PhpStorm's reading of it is the reference. A variadic is its element type there, and
        // an optional parameter is a plain one: PhpStorm has no marker for either.
        myFixture.configureByFiles("closure/Docblock.php")
        val docblock = signatures()
        myFixture.configureByFiles("closure/Spelled.phn")
        val spelled = signatures()

        assertEquals(setOf("log", "accept", "find"), docblock.keys)
        assertEquals(docblock, spelled)
    }

    fun testACallOnAVariadicClosureTypeIsCheckedPerArgument() {
        // PhpStorm holds its own encoding of the assigned literal, `fn (string ...$args)`, beside the
        // declared signature, with the variadic as `string[]`, and may check the call against that:
        // "Parameter type 'string' is not compatible with 'string[]'". The arguments that fit the
        // declared parameter are not reported; the ones that do not still are.
        myFixture.enableInspections(PsalmAdvanceCallableParamsInspection::class.java, PhpParamsInspection::class.java)
        myFixture.configureByFiles("closure/Checkout.phn")

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
            .map { it.text to myFixture.editor.document.getLineNumber(it.startOffset) + 1 }

        assertEquals(listOf("[]" to 41, "[]" to 42), findings)
        val request = PsiTreeUtil.findChildrenOfType(myFixture.file, Field::class.java).single { it.name == "request" }
        val held = request.type.global(project).typesWithParametrisedParts.filter { it.startsWith("\\Closure<") }
        assertTrue("PhpStorm's own encoding of the literal is there too: $held", held.any { "\\string[]" in it })
    }

    fun testAFindingAgainstPhpStormsOwnEncodingGoesWhereTheArgumentFitsTheDeclaration() {
        // Which `\Closure<…>` of a property PhpStorm checks a call against depends on the order of its
        // type set; PhpStorm 2026 also encodes a first-class callable's variadic as an array. The filter
        // decides on the declared signature whichever one was picked.
        myFixture.configureByFiles("closure/Checkout.phn")
        val text = myFixture.editor.document.text
        fun finding(argument: String, declared: String): HighlightInfo {
            val start = text.indexOf(argument)
            return HighlightInfo.newHighlightInfo(HighlightInfoType.WARNING)
                .range(start, start + argument.length)
                .descriptionAndTooltip(PhpBundle.message("parameter.type.is.not.compatible.with.declaration", "string", declared))
                .createUnconditionally()
        }

        val filter = PhaenonHighlightFilter()
        assertFalse("a string for `string...`", filter.accept(finding("'post', 'charges', \$order", "string[]"), myFixture.file))
        assertFalse("the second string for `string...`", filter.accept(finding("'charges', \$order", "string[]"), myFixture.file))
        assertFalse("the variadic after a first parameter", filter.accept(finding("'refunds', 'create'", "string[]"), myFixture.file))
        assertTrue("an array for `string...`", filter.accept(finding("[]);", "string[]"), myFixture.file))
    }

    fun testADestructuredClosureResultIsTypedByTheTupleElements() {
        // `(int, string)` is the union of its element arrays to the editor (README § Limitations), so
        // `[$status]` is `int|string`: no `mixed` and no `string[]`. PhpStorm keeps positions only
        // for docblock shapes it reads from the PSI; its own reading of the compiled
        // `@var \Closure(string...): array{int, string}` types `$status` as `mixed`.
        myFixture.configureByFiles("closure/Checkout.phn")

        val status = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java).first { it.name == "status" }

        assertEquals(listOf("\\int", "\\string"), status.type.global(project).types.filter { it != "?" }.sorted())
    }

    /** The decoded closure signature of each property in the file: one set of types per parameter, then the return. */
    private fun signatures(): Map<String, List<Set<String>>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Field::class.java).associate { field ->
            val encoded = field.type.global(project).typesWithParametrisedParts.single { it.startsWith("\\Closure<") }
            field.name to PhpType.getParametrizedParts(encoded).map { part ->
                PhpExpressionImpl.getTypeFromSerializedClosureParameter(part).second.types.toSet()
            }
        }

    /** `closure/Gateway.phn` with every closure type written `\Closure(…)`, in a namespace of its own. */
    private fun qualifiedCopy(): String =
        File(testDataPath, "closure/Gateway.phn").readText()
            .replace("namespace Acme\\Shop\\Payments;", "namespace Acme\\Shop\\Qualified;")
            .replace(Regex("""(?<![\\\w])Closure\("""), "\\\\Closure(")
            .also { assertEquals(7, Regex("""\\Closure\(""").findAll(it).count()) }

    /** Where "go to declaration" leads from each closure type of the file in the editor: the class and its file. */
    private fun closureTargets(): List<Pair<String, String>> {
        val text = myFixture.editor.document.text
        return Regex("""Closure\(""").findAll(text).map { match ->
            val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, match.range.first + 1)
            val line = myFixture.editor.document.getLineNumber(match.range.first) + 1
            assertEquals("line $line: ${targets.toList()}", 1, targets.size)
            val target = targets.single() as PhpClass
            target.fqn to target.containingFile.virtualFile.path
        }.toList()
    }

    /**
     * The type a declaration spells: the native part PhpStorm parsed and what the type provider
     * reads back from the erased text, without what PhpStorm infers from assignments and bodies.
     */
    private fun declaredType(element: PsiElement): PhpType? {
        if (element !is Parameter && element !is Field && element !is Function) return null
        val native = (element as PhpTypeDeclarationOwner<*>).typeDeclaration?.type ?: PhpType.EMPTY
        return PhpType().add(native).add(PhaenonTypeProvider().getType(element) ?: PhpType.EMPTY)
    }

    /**
     * [type] of every property, parameter, function and property read in [file], keyed `$property`,
     * `function($parameter)`, `function()` for a return type, `->property`.
     */
    private fun types(file: PsiFile, type: (PsiElement) -> PhpType?): Map<String, List<String>> {
        val types = LinkedHashMap<String, List<String>>()
        for (element in PsiTreeUtil.collectElementsOfType(file, PhpTypedElement::class.java)) {
            val key = when (element) {
                is Parameter -> "${PsiTreeUtil.getParentOfType(element, Function::class.java)?.name}(\$${element.name})"
                is Field -> "\$${element.name}"
                is Function -> if (element.isClosure) continue else "${element.name}()"
                is FieldReference -> "->${element.name}"
                else -> continue
            }
            val found = type(element) ?: continue
            types[key] = found.global(project).typesWithParametrisedParts.filter { it != "?" }.sorted()
        }
        return types
    }
}
