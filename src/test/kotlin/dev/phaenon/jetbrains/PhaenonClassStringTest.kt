package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.CtrlMouseHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.parameterInfo.LanguageParameterInfo
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassConstantInspection
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.type.PhpParamsInspection
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.MemberReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * `class<T>` is PHPStan's `class-string<T>`: a static call, `new`, a constant read on it name the
 * class, here `T`'s bound. A generic repository reported "Method 'rebuildFromHistory' not
 * found" on `$class::rebuildFromHistory(…)` with `$class = static::aggregateClass()` and
 * `aggregateClass(): class<T>`; then `$class: string|string` in its hover, the type having been
 * `string` plus the class-string. The type is now the one
 * PhpStorm gives a docblock-only `@return class-string<Foo>`, and the popups print Phaenon's
 * spelling ([PhaenonDocumentationProvider], [PhaenonParameterInfoHandler]).
 */
class PhaenonClassStringTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyFileToProject("hierarchy/Domain.phn")
        myFixture.copyFileToProject("hierarchy/AggregateRepository.phn")
    }

    fun testAStaticCallOnTheRepositorysClassStringResolves() {
        myFixture.enableInspections(PhpUndefinedMethodInspection::class.java)
        myFixture.configureByFiles("hierarchy/Today.phn")

        assertEquals(listOf("\\App\\EventSourcedAggregate.rebuildFromHistory"), targets("rebuildFromHistory"))
        assertEmpty(warnings())
    }

    fun testAClassStringNamesItsClassInEveryPosition() {
        myFixture.enableInspections(PhpUndefinedMethodInspection::class.java, PhpUndefinedClassConstantInspection::class.java)
        myFixture.configureByFiles("classstring/ClassString.phn")

        assertEquals("parameter and property class<User>", listOf("\\App\\User.named", "\\App\\User.named"), targets("named"))
        assertEquals("property class<T>, T of EventSourcedAggregate", listOf("\\App\\EventSourcedAggregate.rebuildFromHistory"), targets("rebuildFromHistory"))
        assertEquals(listOf("\\App\\User.TABLE"), targets("TABLE"))
        assertEquals(listOf("\\App\\EventSourcedAggregate.VERSION"), targets("VERSION"))
        val assigned = assignedTypes()
        assertEquals("new \$users(…)", listOf("\\App\\User"), assigned["fresh"])
        assertEquals("\$users::named(…)", listOf("\\App\\User"), assigned["user"])
        assertEmpty(warnings())
    }

    fun testTheTypeIsTheClassStringOfTheBoundAlone() {
        myFixture.configureByFiles("hierarchy/Today.phn")

        val expected = listOf("\\class-string<\\App\\EventSourcedAggregate>")
        val method = PsiTreeUtil.findChildrenOfType(myFixture.file, Method::class.java).single { it.name == "aggregateClass" }
        assertEquals("the method", expected, phpTypes(method))
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, MemberReference::class.java).single { it.name == "aggregateClass" }
        assertEquals("the call", expected, phpTypes(call))
        val variable = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java).first { it.name == "class" }
        assertEquals("the variable", expected, phpTypes(variable))
    }

    fun testConcreteParameterAndPropertyClassStrings() {
        myFixture.configureByFiles("classstring/ClassString.phn")

        val parameter = PsiTreeUtil.findChildrenOfType(myFixture.file, Variable::class.java).first { it.name == "users" }
        assertEquals("class<User> parameter", listOf("\\class-string<\\App\\User>"), phpTypes(parameter))
        val property = PsiTreeUtil.findChildrenOfType(myFixture.file, MemberReference::class.java).first { it.name == "aggregateClass" }
        assertEquals("class<T> property", listOf("\\class-string<\\App\\EventSourcedAggregate>"), phpTypes(property))
        assertEquals("\$users::class", listOf("\\string"), assignedTypes()["name"])
    }

    fun testAClassStringIsAStringToPhpStormsChecks() {
        myFixture.enableInspections(PhpParamsInspection::class.java, PhpUndefinedMethodInspection::class.java)
        myFixture.copyFileToProject("classstring/ClassString.phn")
        myFixture.configureByFiles("classstring/Uses.phn")

        // `takesString($users)`, `make(User::class, …)`, `instanceof $users`, `new $userClass()`.
        assertEmpty(warnings())
        assertEquals(listOf("\\App\\User"), assignedTypes()["other"])
    }

    fun testHoverAndQuickDocumentationPrintPhaenonsSpelling() {
        myFixture.configureByFiles("hierarchy/Today.phn")

        val declaration = "abstract protected static fun aggregateClass(): class<T>"
        assertEquals("Ctrl+hover on the declaration", declaration, hint("fun aggregateClass", 6))
        assertEquals("Ctrl+hover on the call", declaration, hint("static::aggregateClass", 10))
        assertTrue(doc("static::aggregateClass", 10), doc("static::aggregateClass", 10).contains(declaration))
        assertEquals("\$class : class<EventSourcedAggregate>", hint("\$class =", 1))
        assertTrue(doc("\$class::", 1).contains("\$class: class<EventSourcedAggregate>"))
        assertFalse(doc("\$class::", 1).contains("string"))
    }

    fun testHoverOnParametersAndProperties() {
        myFixture.configureByFiles("classstring/ClassString.phn")

        assertEquals("concrete parameter", "\$users : class<User>", hint("\$users::named", 1))
        assertEquals("class<User> property", "\$userClass : class<User>", hint("this->userClass", 7))
        assertEquals("class<T> property", "\$aggregateClass : class<T>", hint("this->aggregateClass", 7))
        assertEquals("a method with an erased parameter", "fun make(class<User> \$users, AggregateId \$id): void", hint("fun make", 5))
    }

    fun testCtrlHoverAndCtrlClickOnTheGetterLandOnTheMethod() {
        myFixture.configureByFiles("hierarchy/Today.phn")

        for ((needle, shift) in listOf("static::aggregateClass" to 10, "fun aggregateClass" to 6)) {
            val offset = myFixture.file.text.indexOf(needle) + shift
            myFixture.editor.caretModel.moveToOffset(offset)
            val text = CtrlMouseHandler.getGoToDeclarationOrUsagesText(myFixture.editor)?.let(::popupText)
            assertEquals(needle, "abstract protected static fun aggregateClass(): class<T>", text)
        }
        val offset = myFixture.file.text.indexOf("static::aggregateClass") + 10
        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offset)
        assertEquals(listOf("\\App\\AbstractEventSourcedRepository.aggregateClass"), targets.map { (it as PhpNamedElement).fqn })
    }

    fun testParameterInfoPrintsTheWrittenType() {
        myFixture.copyFileToProject("classstring/ClassString.phn")
        myFixture.configureByFiles("classstring/Uses.phn")

        assertEquals("users: class<User>, id: \\App\\AggregateId", parameterInfo("->make(", 7))
        // A function without an erased parameter type keeps PhpStorm's.
        assertEquals("PhpParameterInfoHandler", parameterInfoHandler("takesString(\$users", 12))
    }

    fun testPlainPhpKeepsPhpStormsPresentation() {
        myFixture.copyFileToProject("classstring/ClassString.phn")
        myFixture.configureByFiles("classstring/Plain.php")

        // PhpStorm prints a class-string as `string` (2025.1 and 2026.2); with a native `string`
        // beside the docblock, 2026.2 prints both members: `string|string`. A .php file is
        // PhpStorm's to print.
        assertEquals("\$docOnly : string", hint("\$docOnly =", 1))
        assertTrue(hint("\$withNative =", 1), hint("\$withNative =", 1) in setOf("\$withNative : string", "\$withNative : string|string"))
    }

    private fun phpTypes(element: PsiElement): List<String> =
        (element as PhpTypedElement).type.global(project).typesWithParametrisedParts.filter { !it.startsWith("?") && !it.startsWith("#") }.sorted()

    private fun hint(needle: String, shift: Int): String? = myFixture.hint(needle, shift)

    private fun doc(needle: String, shift: Int): String = myFixture.quickDoc(needle, shift)

    @Suppress("UNCHECKED_CAST")
    private fun handlers() =
        LanguageParameterInfo.INSTANCE.allForLanguage(PhpLanguage.INSTANCE) as List<ParameterInfoHandler<PsiElement, Any>>

    private fun parameterInfoHandler(needle: String, shift: Int): String? {
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(needle) + shift)
        return handlers().firstOrNull { it.findElementForParameterInfo(MockCreateParameterInfoContext(myFixture.editor, myFixture.file)) != null }?.javaClass?.simpleName
    }

    private fun parameterInfo(needle: String, shift: Int): String? {
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf(needle) + shift)
        for (handler in handlers()) {
            val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
            val owner = handler.findElementForParameterInfo(context) ?: continue
            val ui = MockParameterInfoUIContext(owner)
            handler.updateUI(context.itemsToShow!!.single(), ui)
            return ui.text
        }
        return null
    }

    private fun targets(member: String): List<String?> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, MemberReference::class.java)
            .filter { it.name == member }
            .flatMap { (it as PsiPolyVariantReference).multiResolve(false).map { r -> (r.element as? PhpNamedElement)?.fqn } }

    private fun assignedTypes(): Map<String, List<String>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, AssignmentExpression::class.java)
            .filter { it.variable is Variable }
            .associate { (it.variable as Variable).name!! to (it.value as PhpTypedElement).type.global(project).types.filter { t -> t != "?" }.sorted() }

    private fun warnings() = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} '${it.text}'" }
}
