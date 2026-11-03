package dev.phaenon.jetbrains

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.inspections.PhpUndefinedMethodInspection
import com.jetbrains.php.lang.inspections.classes.PhpHierarchyChecksInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingFieldTypeInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingParamTypeInspection
import com.jetbrains.php.lang.inspections.type.PhpMissingReturnTypeInspection
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.ForeachStatement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * What PhpStorm makes of the erased declarations of `testData/types/` and of text written in
 * tests: `list`, `map`, `bytes`, tuples, `class<T>`, closure types and type parameters typed as
 * the compiler erases them, and `->` on a value that is not an object meaning what PHP means by
 * it (roadmap D20).
 */
class PhaenonErasedTypesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("types", "")
    }

    fun testErasedDeclarationsGetTheirTypesBack() {
        val file = myFixture.configureByText(
            "A.phn",
            """
            namespace App;

            final class Box<T>
            {
                public function __construct(
                    private readonly list<User> ${'$'}users,
                    private readonly map<string, User> ${'$'}byName,
                    private readonly ?list<T> ${'$'}maybe,
                    private readonly bytes ${'$'}raw,
                    private readonly (string, int) ${'$'}pair,
                    private readonly class<User> ${'$'}userClass,
                    private readonly \Closure(T): bool ${'$'}pred,
                    private readonly \Closure(string, int=): void ${'$'}log,
                    private readonly \Closure(string, string...): (int, string) ${'$'}kubectl,
                    private readonly T ${'$'}item,
                    private readonly User|list<int> ${'$'}mixed,
                ) {
                }

                public function users(): list<User> { return ${'$'}this->users; }

                public function first<U>(list<U> ${'$'}xs): ?U { return ${'$'}xs[0] ?? null; }
            }

            interface Users
            {
                public function all(): list<User>;
            }
            """.trimIndent(),
        )

        val parameters = PsiTreeUtil.collectElementsOfType(file, Parameter::class.java)
        val types = parameters.associate { it.name to it.type.global(project).typesWithParametrisedParts.sorted() }
        assertEquals(listOf("\\App\\User[]"), types["users"])
        assertEquals("a map is the array of its values", listOf("\\App\\User[]"), types["byName"])
        assertEquals(listOf("\\array", "\\null"), types["maybe"])
        assertEquals(listOf("\\string"), types["raw"])
        assertEquals(listOf("\\int[]", "\\string[]"), types["pair"])
        // As PhpStorm types a docblock-only `@return class-string<User>`: no `string` beside it, which would print as `string|string`.
        assertEquals(listOf("\\class-string<\\App\\User>"), types["userClass"])
        assertEquals(listOf("\\Closure", closure(listOf("\\mixed"), "\\bool")), types["pred"])
        assertEquals(listOf("\\Closure", closure(listOf("\\string", "\\int"), "\\void")), types["log"])
        assertEquals(listOf("\\Closure", closure(listOf("\\string", "\\string"), "\\int[]", "\\string[]")), types["kubectl"])
        assertEquals(listOf("\\mixed"), types["item"])
        assertEquals(listOf("\\App\\User", "\\int[]"), types["mixed"])
        assertEquals(listOf("\\array"), types["xs"])

        val returns = PsiTreeUtil.collectElementsOfType(file, Function::class.java).associate { it.name to it.type.global(project).typesWithParametrisedParts.sorted() }
        assertEquals(listOf("\\App\\User[]"), returns["users"])
        assertEquals(listOf("\\mixed", "\\null"), returns["first"])
        assertEquals("a return type erased whole, colon and all, with no body to infer from", listOf("\\App\\User[]"), returns["all"])
    }

    fun testElementsAreTyped() {
        val file = myFixture.configureByText(
            "A.phn",
            "namespace App;\n\nfunction f(list<User> \$users): void {\n    \$d = \$users[0];\n    foreach (\$users as \$e) {}\n}\n",
        )

        val types = PsiTreeUtil.collectElementsOfType(file, AssignmentExpression::class.java)
            .associate { (it.variable as Variable).name to (it.value as PhpTypedElement).type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted() }
        assertEquals("[0] yields the element type", listOf("\\App\\User"), types["d"])
        val foreachValue = PsiTreeUtil.findChildOfType(file, ForeachStatement::class.java)!!.value!!
        assertEquals("foreach yields the element type", listOf("\\App\\User"), foreachValue.type.global(project).typesWithParametrisedParts.filter { it != "?" }.sorted())
    }

    fun testAMethodCallOnAStringIsPhpsCallOnAString() {
        // D20: `->` means what PHP means by it. A call on a string, a list or bytes resolves to
        // nothing, completion offers nothing, and PhpStorm judges the call as in a .php file.
        myFixture.enableInspections(PhpUndefinedMethodInspection::class.java)
        val call = "    \$name->upper();\n"
        val phn = myFixture.configureByText(
            "OnValues.phn",
            "namespace App;\n\nfunction onValues(string \$name, list<User> \$users, bytes \$raw): void {\n$call    \$users->map(fn (User \$u) => \$u->name);\n    \$raw->hex();\n}\n",
        )

        val targets = PsiTreeUtil.collectElementsOfType(phn, MethodReference::class.java)
            .associate { it.name to it.multiResolve(false).mapNotNull { r -> (r.element as? PhpNamedElement)?.fqn } }
        assertEquals(mapOf("upper" to emptyList<String>(), "map" to emptyList<String>(), "hex" to emptyList<String>()), targets)

        // PhpStorm's "Method 'upper' not found in string", "… in User[]": each call is reported.
        val inPhn = findings()
        assertEquals(inPhn.toString(), listOf("'upper'", "'map'", "'hex'"), inPhn.map { it.substringAfterLast(' ') })
        myFixture.configureByText("OnString.php", "<?php\n\nnamespace App;\n\nfunction onString(string \$name): void {\n$call}\n")
        assertEquals("the call on a string as PhpStorm judges it in PHP", findings(), inPhn.filter { it.endsWith("'upper'") })

        myFixture.configureByText("Complete.phn", "namespace App;\n\nfunction complete(string \$name): void { \$name-><caret> }\n")
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "upper", "startsWith", "length")
    }

    fun testErasedTypesAreNotMissingTypeDeclarations() {
        myFixture.enableInspections(PhpMissingFieldTypeInspection::class.java, PhpMissingParamTypeInspection::class.java, PhpMissingReturnTypeInspection::class.java)
        myFixture.configureByText(
            "Typed.phn",
            """
            namespace App;

            final class Typed
            {
                private list<string> ${'$'}names = [];
                private ${'$'}plain = null;

                public function keys(map<string, int> ${'$'}m): list<string>
                {
                    return array_keys(${'$'}m);
                }
            }
            """.trimIndent(),
        )

        val findings = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }.map { it.text }

        assertDoesntContain(findings, "\$names", "\$m", "keys")
        assertContainsElements(findings, "\$plain")
    }

    fun testAMethodWithErasedTypesIsNotIncompatibleWithItsParent() {
        myFixture.enableInspections(PhpHierarchyChecksInspection::class.java)
        myFixture.configureByText(
            "Hierarchy.phn",
            """
            namespace App;

            final class AggregateId {}

            interface Aggregate
            {
                public function id(): AggregateId;

                public function withId(AggregateId ${'$'}id): static;
            }

            abstract class Base<T of AggregateId> implements Aggregate
            {
                public function __construct(protected T ${'$'}id) {}

                final public function id(): T
                {
                    return ${'$'}this->id;
                }

                public function withId(T ${'$'}id): static
                {
                    return ${'$'}this;
                }
            }

            final class Wrong implements Aggregate
            {
                public function id(): string
                {
                    return '';
                }

                public function withId(string ${'$'}id): static
                {
                    return ${'$'}this;
                }
            }
            """.trimIndent(),
        )

        val incompatible = myFixture.doHighlighting()
            .filter { it.description.orEmpty().contains("must be compatible") }
            .map { it.text to it.description.orEmpty().substringBefore(" with") }

        assertEquals(
            listOf("string" to "Return type declaration must be compatible", "string \$id" to "Declaration must be compatible"),
            incompatible,
        )
    }

    fun testReadonlyPropertiesWithErasedTypesAreNotErrors() {
        myFixture.configureByFile("Calls.phn")

        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }

        assertEmpty("error-level highlighting: " + errors.map { "${it.description} @${it.startOffset} '${it.text}'" }, errors)
    }

    /** The findings at weak warning or above, as `description 'text'`. */
    private fun findings(): List<String> = myFixture.doHighlighting()
        .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }
        .map { "${it.description} '${it.text}'" }
}
