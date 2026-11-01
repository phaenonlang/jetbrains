package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassInspection
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
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import java.io.File

/**
 * `Closure(A, B=, C...): R` is `\Closure` whatever the file's namespace and imports: the compiler
 * reads `Closure(` as a type form and writes `\Closure`. PhpStorm would resolve a bare `Closure`
 * in the file's namespace, so the plugin erases the bare spelling with its signature and gives the
 * declaration `\Closure` back; `\Closure(…)` keeps its name. A `Closure` without a signature is
 * PHP's and resolves as in PHP. The fixtures sit in `namespace Tequila\Tq\Dev`, as the dogfood
 * file that showed "Undefined class 'Closure'" does.
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
        myFixture.configureByFiles("closure/Kubectl.phn")

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
            .map { "${it.description} '${it.text}' @${myFixture.editor.document.getLineNumber(it.startOffset) + 1}" }

        assertEmpty(findings)
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiErrorElement::class.java).map { it.errorDescription })
    }

    fun testABareClosureTypeIsTheClosureClass() {
        myFixture.configureByFiles("closure/Kubectl.phn")

        val types = types(myFixture.file) { (it as PhpTypedElement).type }

        assertEquals("promoted parameter", listOf("\\Closure"), types["__construct(\$accept)"])
        assertEquals("nullable parameter", listOf("\\Closure", "\\null"), types["__construct(\$kubectl)"])
        assertEquals("parameter", listOf("\\Closure"), types["line(\$kubectl)"])
        assertEquals("union member", listOf("\\Closure", "\\int"), types["either(\$test)"])
        assertEquals("return type without a body", listOf("\\Closure"), types["runner()"])
        assertEquals("the promoted property, read to call it", listOf("\\Closure"), types["->accept"])
        // An erased declaration has no type of its own to PhpStorm, which adds what it infers from
        // the assignments and the body (README § Limitations): the closure's own `\Closure<…>`,
        // and `mixed` through `$kubectl ?? …` on a parameter it sees as untyped.
        for (key in listOf("\$kubectl", "->kubectl", "kubectl()")) {
            val type = types.getValue(key)
            assertTrue("$key: $type", "\\Closure" in type && type.all { it.startsWith("\\Closure") || it == "\\mixed" })
        }
    }

    fun testTheQualifiedSpellingDeclaresTheSameTypes() {
        val bare = types(myFixture.configureByFiles("closure/Kubectl.phn").single(), ::declaredType)
        val qualified = types(myFixture.configureByText("Qualified.phn", qualifiedCopy()), ::declaredType)

        assertEquals(bare, qualified)
    }

    fun testABareClosureTypeNavigatesToPhpsClosureBesideANamespaceClassNamedClosure() {
        myFixture.configureByFiles("closure/Kubectl.phn", "closure/Decoy.phn")
        val bareTargets = closureTargets()
        myFixture.configureByText("Qualified.phn", qualifiedCopy())
        val qualifiedTargets = closureTargets()

        assertEquals(List(7) { "\\Closure" to "/src/stubs/Closure.php" }, bareTargets)
        assertEquals(bareTargets, qualifiedTargets)
        val decoy = PhpIndex.getInstance(project).getClassesByFQN("\\Tequila\\Tq\\Dev\\Closure").single()
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
        assertEquals(listOf("\\Tequila\\Tq\\Dev\\Closure"), parameter.type.global(project).types.sorted())
        assertEmpty(PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) })
    }

    /** `closure/Kubectl.phn` with every closure type written `\Closure(…)`, in a namespace of its own. */
    private fun qualifiedCopy(): String =
        File(testDataPath, "closure/Kubectl.phn").readText()
            .replace("namespace Tequila\\Tq\\Dev;", "namespace Tequila\\Tq\\Qualified;")
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
