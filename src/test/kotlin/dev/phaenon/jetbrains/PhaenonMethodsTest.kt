package dev.phaenon.jetbrains

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
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
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpTypedElement
import com.jetbrains.php.lang.psi.elements.Variable

/**
 * The method syntax of `design/collections.md` §3.1 and `strings.md` §4 in the editor: what
 * `->` offers on a list, a map, a string and bytes, where the calls resolve, and what PhpStorm
 * makes of the erased declarations.
 */
class PhaenonMethodsTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.copyDirectoryToProject("methods", "")
    }

    fun testListReceiverCompletesTheSeqMethods() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(list<User> \$users): void { \$users-><caret> }\n")

        myFixture.completeBasic()

        val lookup = myFixture.lookupElementStrings!!
        assertContainsElements(lookup, "map", "filter", "first", "keyBy", "count")
        assertDoesntContain(lookup, "get", "has", "upper", "hex")
    }

    fun testMapReceiverCompletesTheMapMethods() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(map<string, User> \$m): void { \$m-><caret> }\n")

        myFixture.completeBasic()

        val lookup = myFixture.lookupElementStrings!!
        assertContainsElements(lookup, "get", "has", "keys", "values", "toList")
        assertDoesntContain(lookup, "first", "sortBy")
    }

    fun testStringReceiverCompletesTheStrMethods() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(string \$name): void { \$name-><caret> }\n")

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings!!, "upper", "startsWith", "split", "bytes")
    }

    fun testBytesReceiverCompletesTheBytesMethods() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(bytes \$raw): void { \$raw-><caret> }\n")

        myFixture.completeBasic()

        val lookup = myFixture.lookupElementStrings!!
        assertContainsElements(lookup, "hex", "text", "base64")
        assertDoesntContain(lookup, "upper", "map")
    }

    fun testStringableReceiverHasTheStrMethodsAfterItsOwn() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(Id \$id): void { \$id-><caret> }\n")

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings!!, "value", "startsWith")
    }

    fun testMethodCallsResolveToThePseudoClasses() {
        val file = myFixture.configureByFile("Calls.phn")

        val resolved = PsiTreeUtil.collectElementsOfType(file, MethodReference::class.java)
            .filter { it.name in setOf("filter", "map", "get", "hex", "startsWith", "value", "trim") }
            .associate { it.name!! to (it.resolve() as? Method)?.containingClass?.fqn }
        assertEquals(
            mapOf(
                "filter" to "\\Phaenon\\Internal\\ListMethods",
                "map" to "\\Phaenon\\Internal\\ListMethods",
                "get" to "\\Phaenon\\Internal\\MapMethods",
                "hex" to "\\Phaenon\\Internal\\BytesMethods",
                "startsWith" to "\\Phaenon\\Internal\\StrMethods",
                "value" to "\\App\\Id",
                "trim" to "\\Phaenon\\Internal\\StrMethods",
            ),
            resolved,
        )
    }

    fun testGoToDeclarationLandsOnTheRuntimeFunction() {
        myFixture.configureByText("A.phn", "namespace App;\n\nfunction f(list<User> \$users, string \$s): void { \$users->ma<caret>p(fn (User \$u) => \$u->name); \$s->upper(); }\n")

        val targets = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)

        val method = targets.filterIsInstance<Method>().single()
        assertEquals("\\Phaenon\\Seq", method.containingClass?.fqn)
        assertEquals("map", method.name)
        assertEquals("Seq.php", method.containingFile.name)
    }

    fun testUndefinedMethodInspectionAcceptsTheMethodSyntax() {
        myFixture.enableInspections(PhpUndefinedMethodInspection::class.java)
        myFixture.configureByFile("Calls.phn")

        val warnings = myFixture.doHighlighting().filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal }

        assertEquals("only the deliberately broken calls should be reported, got: $warnings", listOf("nonexistent", "alsoNonexistent"), warnings.map { it.text })
    }

    fun testErasedDeclarationsGetTheirTypesBack() {
        val file = myFixture.configureByText(
            "A.phn",
            """
            namespace App;

            use Phaenon\Seq;

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
                    private readonly Seq|list<int> ${'$'}mixed,
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
        assertEquals(listOf("\\App\\User[]", "\\Phaenon\\Internal\\ListMethods<\\App\\User>"), types["users"])
        assertEquals(listOf("\\App\\User[]", "\\Phaenon\\Internal\\MapMethods<string,\\App\\User>"), types["byName"])
        assertEquals(listOf("\\Phaenon\\Internal\\ListMethods<mixed>", "\\array", "\\null"), types["maybe"])
        assertEquals(listOf("\\Phaenon\\Internal\\BytesMethods", "\\string"), types["raw"])
        assertEquals(listOf("\\int[]", "\\string[]"), types["pair"])
        assertEquals(listOf("\\class-string<\\App\\User>", "\\string"), types["userClass"])
        assertEquals(listOf("\\Closure"), types["pred"])
        assertEquals(listOf("\\Closure"), types["log"])
        assertEquals(listOf("\\Closure"), types["kubectl"])
        assertEquals(listOf("\\mixed"), types["item"])
        assertEquals(listOf("\\Phaenon\\Internal\\ListMethods<int>", "\\Phaenon\\Seq", "\\int[]"), types["mixed"])
        assertEquals(listOf("\\Phaenon\\Internal\\ListMethods<mixed>", "\\array"), types["xs"])

        val returns = PsiTreeUtil.collectElementsOfType(file, Function::class.java).associate { it.name to it.type.global(project).typesWithParametrisedParts.sorted() }
        assertEquals(listOf("\\App\\User[]", "\\Phaenon\\Internal\\ListMethods<\\App\\User>"), returns["users"])
        assertEquals(listOf("\\mixed", "\\null"), returns["first"])
        assertEquals("a return type erased whole, colon and all, with no body to infer from", listOf("\\App\\User[]", "\\Phaenon\\Internal\\ListMethods<\\App\\User>"), returns["all"])
    }

    fun testChainsAndElementsAreTyped() {
        val file = myFixture.configureByText(
            "A.phn",
            "namespace App;\n\nfunction f(list<User> \$users): void {\n    \$a = \$users->filter(fn (User \$u) => \$u->active);\n    \$b = \$users->first();\n    \$c = \$users->map(fn (User \$u) => \$u->name);\n    \$d = \$users[0];\n    foreach (\$users as \$e) {}\n}\n",
        )

        // PhpStorm marks the array half of `User[]|ListMethods<User>` as "no such method" (`?`); the
        // other half carries the answer, so the marker is left out of the comparison.
        val types = PsiTreeUtil.collectElementsOfType(file, AssignmentExpression::class.java)
            .associate { (it.variable as Variable).name to (it.value as PhpTypedElement).type.global(project).typesWithParametrisedParts.filter { t -> t != "?" }.sorted() }
        assertEquals("filter keeps the element type for the next link", listOf("\\App\\User[]", "\\Phaenon\\Internal\\ListMethods", "\\Phaenon\\Internal\\ListMethods<\\App\\User>", "\\array"), types["a"])
        assertEquals("first() yields the element type", listOf("\\App\\User", "\\mixed", "\\null"), types["b"])
        assertEquals("map() takes the closure's return type", listOf("\\Phaenon\\Internal\\ListMethods", "\\Phaenon\\Internal\\ListMethods<string>", "\\array", "\\string[]"), types["c"])
        assertEquals("[0] yields the element type", listOf("\\App\\User", "\\mixed"), types["d"])
        val foreachValue = PsiTreeUtil.findChildOfType(file, ForeachStatement::class.java)!!.value!!
        assertEquals("foreach yields the element type", listOf("\\App\\User", "\\mixed"), foreachValue.type.global(project).typesWithParametrisedParts.filter { it != "?" }.sorted())
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
                    return ${'$'}m->keys();
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
}
