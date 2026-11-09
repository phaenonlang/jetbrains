package dev.phaenon.jetbrains

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.classes.PhpHierarchyChecksInspection
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpNamedElement

/**
 * A type parameter erases to its bound, and an override is judged with the types the compiler
 * writes. The fixtures (`testData/hierarchy/`) model an event-sourced repository,
 * `AggregateRepository<Id of AggregateId, T of Aggregate>` and `AbstractEventSourcedRepository<Id,
 * T of EventSourcedAggregate>`, where PhpStorm reported "Declaration must be compatible with
 * AggregateRepository->save(aggregate: mixed)": to PhpStorm the interface's `save(T $aggregate)`
 * had no type at all.
 */
class PhaenonHierarchyTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.enableInspections(PhpHierarchyChecksInspection::class.java, PhaenonHierarchyInspection::class.java)
        myFixture.copyFileToProject("hierarchy/Domain.phn")
        myFixture.copyFileToProject("hierarchy/AggregateRepository.phn")
    }

    fun testATypeParameterIsTypedAsItsBound() {
        myFixture.configureByFiles("hierarchy/AggregateRepository.phn")
        val parameters = parameterTypes()
        val returns = returnTypes()

        assertEquals("save(T \$aggregate), T of Aggregate", listOf("\\App\\Aggregate"), parameters["save(\$aggregate)"])
        assertEquals("get(Id \$id), Id of AggregateId", listOf("\\App\\AggregateId"), parameters["get(\$id)"])
        assertEquals("?T", listOf("\\App\\Aggregate", "\\null"), returns["get()"])
        assertEquals("T", listOf("\\App\\Aggregate"), returns["load()"])

        myFixture.configureByText(
            "Positions.phn",
            """
            namespace App;

            final class Positions<T of Aggregate, U>
            {
                private ?T ${'$'}last = null;

                union(int|T ${'$'}x, T|null ${'$'}y, U ${'$'}z, list<T> ${'$'}all): void
                {
                }
            }
            """.trimIndent(),
        )
        val positions = parameterTypes()
        assertEquals(listOf("\\App\\Aggregate", "\\int"), positions["union(\$x)"])
        assertEquals(listOf("\\App\\Aggregate", "\\null"), positions["union(\$y)"])
        assertEquals("unbounded", listOf("\\mixed"), positions["union(\$z)"])
        assertEquals(listOf("\\App\\Aggregate[]"), positions["union(\$all)"])
        val property = PsiTreeUtil.findChildrenOfType(myFixture.file, com.jetbrains.php.lang.psi.elements.Field::class.java).single { it.name == "last" }
        assertEquals(listOf("\\App\\Aggregate", "\\null"), property.type.global(project).typesWithParametrisedParts.sorted())
    }

    fun testATypeParameterResolvesMembersAgainstItsBound() {
        myFixture.enableInspections(PhpUndefinedMethodInspection::class.java)
        myFixture.configureByFiles("hierarchy/Companion.phn")

        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, MethodReference::class.java).single { it.name == "getUncommittedEvents" }
        val targets = (call as PsiPolyVariantReference).multiResolve(false).map { (it.element as PhpNamedElement).fqn }

        assertEquals(listOf("\\App\\EventSourcedAggregate.getUncommittedEvents"), targets)
        assertEmpty(findings { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING.myVal })
    }

    fun testTheRepositoryAsItReadsTodayIsCompatible() {
        myFixture.configureByFiles("hierarchy/Today.phn")

        assertEmpty(incompatible())
    }

    fun testATypeParameterImplementingTheInterfacesTypeParameterIsCompatible() {
        // `save(T $aggregate)`, T of EventSourcedAggregate, implements `save(T $aggregate)`, T of
        // Aggregate: the interface's T is the class's own through `implements AggregateRepository<Id, T>`.
        myFixture.configureByFiles("hierarchy/Companion.phn")

        assertEmpty(incompatible())
    }

    fun testARealIncompatibilityIsStillReported() {
        myFixture.configureByFiles("hierarchy/Incompatible.phn")

        assertEquals(
            listOf(
                "?string" to "Return type declaration must be compatible with AggregateRepository->get() : ?\\App\\Aggregate",
                "string \$aggregate" to "Declaration must be compatible with AggregateRepository->save(aggregate: \\App\\Aggregate)",
                "T \$aggregate" to "Declaration must be compatible with PlainStore->save(aggregate: \\App\\Aggregate)",
                "EventSourcedAggregate \$aggregate" to "Declaration must be compatible with AggregateRepository->save(aggregate: \\App\\Aggregate)",
                "save" to "Declaration must be compatible with AggregateRepository->save(aggregate: \\App\\Aggregate)",
            ),
            incompatible(),
        )
    }

    fun testTypeArgumentsAreSubstitutedThroughTheHierarchy() {
        // Box<T>::accept(T) is accept(mixed) to PHP; IntBox extends Box<int> may say accept(int), as the
        // checker allows and the compiler writes accept(mixed). StringBox may not; Bottom gets Box's T
        // through Middle<U> extends Box<list<U>>.
        myFixture.configureByFiles("hierarchy/Specialised.phn")

        assertEquals(
            listOf(
                "string \$value" to "Declaration must be compatible with Box->accept(value: mixed)",
                "string" to "Return type declaration must be compatible with Box->get() : mixed",
            ),
            incompatible(),
        )
    }

    fun testANativeTypeEqualToTheParentsErasureInheritsTheParentsType() {
        // generics.md §11.1: `normalize(object $value): mixed` under `normalize(PhpType $value): DbType`
        // is the parent's signature, whatever the arguments (`Normalizer<Level, string|int>`); the
        // checker then judges the body against the inherited type. Type arguments of a native name
        // (`Command<TResult>`, `iterable<T>`) erase with it.
        myFixture.configureByFiles("hierarchy/Inherited.phn")

        assertEmpty(incompatible())
    }

    fun testAPhpClassImplementingAPhaenonInterfaceIsJudgedWithTheErasedTypes() {
        myFixture.configureByFiles("hierarchy/PlainRepository.php")

        assertEquals(
            listOf(
                "?string" to "Return type declaration must be compatible with AggregateRepository->get() : ?\\App\\Aggregate",
                "string \$aggregate" to "Declaration must be compatible with AggregateRepository->save(aggregate: \\App\\Aggregate)",
            ),
            incompatible(),
        )
    }

    private fun incompatible(): List<Pair<String, String>> =
        findings { it.description.orEmpty().contains("must be compatible") }.map { it.text to it.description }

    private fun findings(filter: (HighlightInfo) -> Boolean): List<HighlightInfo> = myFixture.doHighlighting().filter(filter)

    private fun parameterTypes(): Map<String, List<String>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Parameter::class.java).associate {
            "${PsiTreeUtil.getParentOfType(it, Function::class.java)?.name}(\$${it.name})" to it.type.global(project).typesWithParametrisedParts.sorted()
        }

    private fun returnTypes(): Map<String, List<String>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Method::class.java).associate {
            "${it.name}()" to it.type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted()
        }
}
