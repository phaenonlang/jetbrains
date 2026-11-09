package dev.phaenon.jetbrains

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

/**
 * A method is declared without a keyword (roadmap D27, design/functions.md §1.1): at the member
 * level of a class, interface, trait or enum body, anonymous classes included, `[modifiers]
 * [&]name[<…>](` is a method, which [PhaenonLexer] hands PhpStorm's parser with an empty
 * `function` token before it. `testData/methods/Shapes.phn` holds every shape: each modifier
 * combination, abstract and interface methods, enum and trait methods, property hooks beside
 * methods, a property with a DNF type after `static` and one with a closure type, generic methods,
 * reserved words as names, the names that keep PHP's `function`, a function declared in a
 * method's body, and calls there. `fun` is an identifier again. A method without a visibility is
 * public, and a closure in a constant expression is what the compiler lowers to `static function`
 * (design/functions.md §2).
 */
class PhaenonMethodsTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
    }

    private fun shapes() = myFixture.configureByFile("methods/Shapes.phn")

    private fun klass(name: String): PhpClass = PsiTreeUtil.findChildrenOfType(myFixture.file, PhpClass::class.java).single { it.name == name }

    /** The methods [name] declares, in the order of the source. */
    private fun methods(name: String): Map<String, Method> = PsiTreeUtil.getChildrenOfTypeAsList(klass(name), Method::class.java).associateBy { it.name }

    fun testEveryShapeParsesWithoutErrors() {
        val file = shapes()

        val errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java)
        assertEmpty("syntax errors: " + errors.map { "'${it.errorDescription}' at ${it.textOffset} near '${it.parent.text.take(40)}'" }, errors)
        assertEquals(file.virtualFile.contentsToByteArray().toString(Charsets.UTF_8), file.text)
        PsiTestUtil.checkStubsMatchText(file)
    }

    fun testTheDeclarationsAtMemberLevelAreMethodsAndTheOthersAreNot() {
        shapes()

        assertEquals(listOf("__construct"), methods("Tag").keys.toList())
        assertEquals(listOf("area", "unit", "scaled"), methods("Shape").keys.toList())
        assertEquals(
            listOf(
                "__construct", "area", "perimeter", "unit", "scaled", "create", "quiet", "loud", "log", "kind", "describe", "registry", "ordered",
                "rows", "__toString", "first", "pair", "list", "use", "new", "fun", "static", "readonly", "interface", "trait", "class", "legacy",
            ),
            methods("Base").keys.toList(),
        )
        assertEquals(listOf("name", "label"), methods("Named").keys.toList())
        assertEquals(listOf("label", "fromChar", "jsonSerialize", "case"), methods("Suit").keys.toList())
        assertEquals(listOf("__construct", "area", "perimeter", "label"), methods("Square").keys.toList())
        val anonymous = PsiTreeUtil.findChildrenOfType(myFixture.file, PhpClass::class.java).single { it.isAnonymous }
        assertEquals(listOf("__construct", "double"), PsiTreeUtil.getChildrenOfTypeAsList(anonymous, Method::class.java).map { it.name })

        // A property after `static (`, a closure-typed one and the hooked one are properties.
        assertSameElements(klass("Base").ownFields.filter { !it.isConstant }.map { it.name }, "items", "format", "count", "sides", "cache")
        assertSameElements(klass("Base").ownFields.filter { it.isConstant }.map { it.name }, "LIMIT", "DOUBLE")
        assertEquals(listOf("name"), klass("Named").ownFields.map { it.name })
        // A function declared in a method's body, and the top-level one, are functions; a call in a body is a call.
        val functions = PsiTreeUtil.findChildrenOfType(myFixture.file, Function::class.java).filter { it !is Method && !it.isClosure }.map { it.name }
        assertEquals(listOf("twice", "label"), functions)
        val calls = PsiTreeUtil.findChildrenOfType(myFixture.file, FunctionReference::class.java).map { it.name }
        assertContainsElements(calls, "twice", "label", "sprintf", "error_log")
    }

    fun testModifiersAreReadAndNoVisibilityIsPublic() {
        shapes()
        val base = methods("Base")

        fun modifiers(method: Method) = listOfNotNull(
            "abstract".takeIf { method.isAbstract },
            "final".takeIf { method.isFinal },
            method.access.toString(),
            "static".takeIf { method.isStatic },
        ).joinToString(" ")

        assertEquals("abstract public", modifiers(base.getValue("area")))
        assertEquals("abstract protected", modifiers(base.getValue("perimeter")))
        assertEquals("public static", modifiers(base.getValue("unit")))
        assertEquals("public", modifiers(base.getValue("scaled")))
        assertEquals("protected static", modifiers(base.getValue("create")))
        assertEquals("private", modifiers(base.getValue("quiet")))
        assertEquals("final public", modifiers(base.getValue("describe")))
        assertEquals("final protected static", modifiers(base.getValue("registry")))
        assertEquals("final public static", modifiers(base.getValue("ordered")))
        assertEquals("public", modifiers(base.getValue("__construct")))
        assertEquals("public", modifiers(base.getValue("legacy")))
        assertEquals("public static", modifiers(methods("Suit").getValue("fromChar")))
        assertEquals("abstract public", modifiers(methods("Named").getValue("label")))
        assertTrue(methods("Shape").values.all { it.access.isPublic })
    }

    fun testTheKeywordLeafIsEmptyWhereTheSourceWroteNone() {
        shapes()

        // A property hook (`name::get`) is a method to PhpStorm, without a keyword in PHP too.
        val keywords = PsiTreeUtil.findChildrenOfType(myFixture.file, Method::class.java)
            .filter { "::" !in it.name }
            .associate { it.name to it.node.findChildByType(PhpTokenTypes.kwFUNCTION)?.text }
        assertEquals("every method has PHP's `function` leaf", emptyList<String>(), keywords.filterValues { it == null }.keys.toList())
        assertEquals(listOf("static", "readonly", "trait", "legacy"), keywords.filterValues { !it.isNullOrEmpty() }.keys.toList())
        // `&` returns by reference, after the empty keyword as after PHP's.
        val rows = methods("Base").getValue("rows")
        assertEquals("&", rows.nameIdentifier?.let { PsiTreeUtil.prevVisibleLeaf(it) }?.text)
        // A generic method's list is erased as it was after `fun`.
        val erased = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java).filter { PhaenonTokenTypes.isErased(it) }.map { it.text }
        assertContainsElements(erased, "<T>", "<K, V of object>", "list<T>", ": (K, V)", "Closure(int): string")
    }

    fun testTheParsersLexerInsertsAnEmptyFunctionOnlyAtMemberLevel() {
        val text = "class A { #[X] a() {} public static b<T>(): void { c(); } &d(): array { return []; } public int \$e { get => f(); set(int \$v) { g(); } }" +
            " r(): void => s(); t(): int => match (1) { default => u() };" +
            " interface(): void { v(); } public function trait(): void { w(); } function &class(): void { x(); } }" +
            " function h() { return new class { i() {} }; } j(); interface K { l(); } trait M { n() {} } enum O { case P; q() {} case() {} }"
        val lexer = PhaenonLexer()
        lexer.start(text)
        val inserted = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType === PhaenonTokenTypes.FUNCTION) {
                assertEquals("an empty token", lexer.tokenStart, lexer.tokenEnd)
                inserted.add(Regex("""[&\w]+""").find(text, lexer.tokenStart)!!.value)
            }
            if (lexer.tokenType === PhpTokenTypes.kwFUNCTION) inserted.add("function")
            lexer.advance()
        }

        assertEquals(listOf("a", "b", "&d", "r", "t", "interface", "function", "function", "function", "i", "l", "n", "q", "case"), inserted)
    }

    fun testFunIsAnIdentifierAndALeftoverDeclarationIsPhpsError() {
        // A function or method named `fun` is PHP code; a declaration still spelled with `fun` is the
        // syntax error PHP makes of it (the compiler's P1001 names the rewrite, through `phn lsp`).
        val names = myFixture.configureByText("Fun.phn", "function fun(): int { return fun(); }\n\nfinal class F\n{\n    fun(): int => fun();\n}\n")
        assertEmpty(PsiTreeUtil.collectElementsOfType(names, PsiErrorElement::class.java))
        assertEquals(listOf("fun", "fun"), PsiTreeUtil.findChildrenOfType(names, Function::class.java).map { it.name })

        val source = "final class G\n{\n    fun g(): int\n    {\n        return 1;\n    }\n}\n\nfun h(): int\n{\n    return 1;\n}\n"
        val phn = myFixture.configureByText("Left.phn", source)
        val php = myFixture.configureByText("Left.php", "<?php\n$source")
        val phnErrors = PsiTreeUtil.collectElementsOfType(phn, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset }
        val phpErrors = PsiTreeUtil.collectElementsOfType(php, PsiErrorElement::class.java).map { it.errorDescription to it.textOffset - "<?php\n".length }
        assertNotEmpty(phnErrors)
        assertEquals(phpErrors, phnErrors)
    }

    fun testTheEditorsLexerIsPhpStormsOwn() {
        // PhpStorm colours a method's name as the identifier it is; nothing is inserted for the editor.
        val file = myFixture.configureByText("E.phn", "final class E\n{\n    e(): void {}\n}\n")

        val lexer = PhaenonBaseLexerProvider().getBaseLexer(project, file.virtualFile)

        assertInstanceOf(lexer, PhpWithOptionalOpenTagLexer::class.java)
    }

    fun testNoMethodIsMissingAVisibility() {
        val tools = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.shortName.orEmpty().contains("Visibility") || it.displayName.orEmpty().contains("visibility", ignoreCase = true) }
        myFixture.enableInspections(*tools.map { it.instantiateTool() }.toTypedArray())
        shapes()

        val findings = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal && it.description.orEmpty().contains("visibility", ignoreCase = true) }
            .map { myFixture.editor.document.getLineNumber(it.startOffset) + 1 }

        // Without a keyword or with PHP's (`function legacy()`), a constant, `private(set)`: all public.
        assertEmpty(findings)
    }

    fun testAClosureInAConstantExpressionIsNotAnError() {
        shapes()

        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.text to it.description }

        assertEmpty(errors)
    }
}
