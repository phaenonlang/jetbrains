package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle

/**
 * Code | Implement Methods, Code | Override Methods and the override items of completion write
 * Phaenon in a `.phn` class ([PhaenonOverrides]): a method without a keyword, and without `public`
 * (`protected` and `static` written), the parent's declared types from its source with its type
 * parameters replaced by the class's type arguments, the classes those types name imported. A
 * `.php` file keeps PhpStorm's PHP.
 *
 * The parents are `testData/implement/AggregateRepository.phn` (an interface with type parameters)
 * and `AbstractEventSourcedRepository.phn` (an abstract class implementing it); their classes are
 * in `Model.phn`. Without a dialog the actions take every candidate.
 */
class PhaenonOverridesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
        for (name in listOf("AggregateRepository.phn", "AbstractEventSourcedRepository.phn", "Model.phn")) myFixture.copyFileToProject("implement/$name")
    }

    fun testImplementMethodsWritesMethodsWithoutAKeywordAndTheTypesTheClassGives() {
        myFixture.configureByFile("implement/UserRepository.phn")

        myFixture.performEditorAction("ImplementMethods")

        // aggregateClass() from the abstract class; load() and save() from its interface, whose
        // `<Id, T>` the abstract class passes on as its own. The interface's bare `@throws` tag is
        // load()'s `throws` clause, its docblock gone with it (design/functions.md §4).
        myFixture.checkResultByFile("implement/UserRepository.implement.after.phn")
    }

    fun testOverrideMethodsKeepsVisibilityAndImportsWhatTheTypesName() {
        myFixture.configureByFile("implement/UserRepository.phn")

        myFixture.performEditorAction("OverrideMethods")

        // `list<DomainEvent>` names a class the parent imports: the class imports it too, as it imports
        // what the parent's `throws` clause names. `mapped<U>` keeps its own type parameter;
        // `protected eventStore()` its visibility. Braces stand on the declarations' lines.
        myFixture.checkResultByFile("implement/UserRepository.override.after.phn")
    }

    fun testAnInterfacesMethodsGetTheArgumentsOfTheImplementsClause() {
        myFixture.configureByFile("implement/Repo.phn")

        myFixture.performEditorAction("ImplementMethods")

        myFixture.checkResultByFile("implement/Repo.after.phn")
    }

    /**
     * A generated body with nothing in it is `{}` on the declaration's line (design/functions.md
     * §5.1), as the reformatting that follows the action lays it out; with PhpStorm's `// TODO`
     * comment (the default template) the body keeps its lines, as `Repo.after.phn` shows.
     */
    fun testAnEmptyGeneratedBodyIsTwoBracesOnTheDeclarationsLine() {
        val template = com.intellij.ide.fileTemplates.FileTemplateManager.getInstance(project).getCodeTemplate("PHP Implemented Method Body.php")
        val text = template.text
        try {
            template.text = ""
            myFixture.configureByFile("implement/Repo.phn")

            myFixture.performEditorAction("ImplementMethods")

            val document = myFixture.editor.document.text
            assertTrue(document, document.contains("    get(UserId \$id): ?User {}\n"))
            assertTrue(document, document.contains("    load(UserId \$id): User throws AggregateNotFound {}\n"))
            assertTrue(document, document.contains("    save(User \$aggregate): void {}\n"))
        } finally {
            template.text = text
        }
    }

    fun testWithoutTypeArgumentsTheParentsSpellingStays() {
        myFixture.configureByFile("implement/Raw.phn")

        myFixture.performEditorAction("ImplementMethods")

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    protected static aggregateClass(): class<T> {\n"))
        // The interface's bare `@throws` tag is a `throws` clause in Phaenon (design/functions.md §4).
        assertTrue(text, text.contains("    load(Id \$id): T throws AggregateNotFound {\n"))
    }

    fun testCompletionOffersOverridesSpelledAsPhaenon() {
        myFixture.configureByFile("implement/Repo.phn")

        val items = myFixture.completeBasic().orEmpty().filter { it.`object` is com.jetbrains.php.lang.psi.elements.Method }
        val shown = items.map { LookupElementPresentation.renderElement(it).let { p -> p.itemText + p.tailText } }

        assertEquals(listOf("get(UserId \$id): ?User {...}", "load(UserId \$id): User {...}", "save(User \$aggregate): void {...}"), shown)
    }

    fun testAnOverrideItemInsertsAPhaenonMethod() {
        myFixture.configureByFile("implement/Repo.phn")
        myFixture.type("lo")

        // The one item that matches is inserted without a list.
        myFixture.completeBasic()?.let { items ->
            myFixture.lookup.currentItem = items.single { it.`object` is com.jetbrains.php.lang.psi.elements.Method && it.lookupString == "load" }
            myFixture.finishLookup('\n')
        }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("{\n    load(UserId \$id): User throws AggregateNotFound {\n        // TODO: Implement load() method.\n    }\n}"))
    }

    fun testAProtectedOverrideItemIsShownWithItsModifiers() {
        myFixture.configureByText(
            "Child.phn",
            "namespace App\\User;\n\nuse App\\EventSourcing\\Repository\\AbstractEventSourcedRepository;\n\n" +
                "final readonly class Child extends AbstractEventSourcedRepository<UserId, User> {\n    aggr<caret>\n}\n",
        )

        val items = myFixture.completeBasic().orEmpty().filter { it.`object` is com.jetbrains.php.lang.psi.elements.Method }

        assertEquals(listOf("protected static aggregateClass"), items.map { it.lookupString })
    }

    fun testCompletingTheNameAfterFunctionKeepsWhatWasWritten() {
        myFixture.configureByText(
            "Child.phn",
            "namespace App\\User;\n\nuse App\\EventSourcing\\Repository\\AbstractEventSourcedRepository;\n\n" +
                "final readonly class Child extends AbstractEventSourcedRepository<UserId, User> {\n    protected static aggregateClass(): class<User> {\n        return User::class;\n    }\n\n    public function ge<caret>\n}\n",
        )

        myFixture.completeBasic()?.let { items -> myFixture.lookup.currentItem = items.first { it.lookupString == "get" }; myFixture.finishLookup('\n') }

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    public function get(UserId \$id): ?User {\n        return parent::get(\$id);"))
    }

    fun testAPhpFileKeepsPhpStormsMethods() {
        myFixture.addFileToProject("Shape.php", "<?php\nnamespace App;\n\ninterface Shape\n{\n    public function area(int \$scale): float;\n}\n")
        myFixture.configureByText("Square.php", "<?php\nnamespace App;\n\nfinal class Square implements Shape\n{\n    <caret>\n}\n")

        myFixture.performEditorAction("ImplementMethods")

        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    public function area(int \$scale): float\n"))
    }
}
