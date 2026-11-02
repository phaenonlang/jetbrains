package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedClassConstantInspection
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.MemberReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * `class<T>` is PHPStan's `class-string<T>`: a static call, `new`, a constant read on it name the
 * class, here `T`'s bound. tequila/core's repository reported "Method 'rebuildFromHistory' not
 * found in \class-string|\Phaenon\Internal\StrMethods" on `$class::rebuildFromHistory(…)` with
 * `$class = static::aggregateClass()` and `aggregateClass(): class<T>`.
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

    fun testAClassStringIsAStringBehindTheArrowOnly() {
        // The language lets a class<T> use the Str methods (`$users->length()` compiles to
        // `Str::length($users)`); a `::` access names a member of the class and never gets them.
        myFixture.configureByFiles("classstring/ClassString.phn")

        assertEquals(listOf("\\Phaenon\\Internal\\StrMethods.length"), targets("length"))
        val staticReceivers = PsiTreeUtil.findChildrenOfType(myFixture.file, MemberReference::class.java)
            .filter { it.isStatic && it.classReference is Variable }
            .map { (it.classReference as PhpTypedElement).type.global(project).types }
        assertTrue(staticReceivers.isNotEmpty())
        assertTrue(staticReceivers.toString(), staticReceivers.none { PhaenonTypeText.STR_METHODS in it })
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
