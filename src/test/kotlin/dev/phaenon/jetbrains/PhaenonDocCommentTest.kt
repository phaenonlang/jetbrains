package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle

/**
 * Enter after the opening of a docblock in a `.phn` file ([PhaenonDocCommentEnterHandler]): the
 * stub is PhpStorm's, aligned by the formatter, without a `@param` for a parameter that has a type,
 * a `@return` for a declared return type, or a `@var` for a typed property — erased types included,
 * which PhpStorm took for no type (`@return mixed` over `class<T>`) — nor a `@throws` the signature's
 * `throws` clause says; a stub of one line or none is one line (design/functions.md §5). A `.php`
 * file keeps PhpStorm's.
 *
 * The delegate finds PhpStorm's stub by asking the delegates registered after it, as the platform
 * would have: the last tests put delegates of their own in that round and check that each is asked
 * once and that the first to decide is obeyed.
 */
class PhaenonDocCommentTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        PSR12CodeStyle().apply(CodeStyle.getSettings(project))
    }

    private fun enter(name: String, text: String): String {
        myFixture.configureByText(name, text)
        myFixture.type("\n")
        val document = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        return document.substring(0, caret) + "<caret>" + document.substring(caret)
    }

    fun testAStubOverATypedMethodIsEmpty() {
        val result = enter(
            "A.phn",
            "namespace App;\n\nabstract class A<T of object>\n{\n    /**<caret>\n    abstract protected static aggregateClass(): class<T>;\n}\n",
        )

        assertEquals(
            "namespace App;\n\nabstract class A<T of object>\n{\n    /** <caret> */\n    abstract protected static aggregateClass(): class<T>;\n}\n",
            result,
        )
    }

    fun testOnlyAnUntypedParameterKeepsItsTag() {
        val result = enter(
            "B.phn",
            "namespace App;\n\nfinal class B<T of object>\n{\n    /**<caret>\n    get(Id \$id, \$raw, list<int> \$xs, string|list<int> \$e, int ...\$n): ?T\n    {\n        return null;\n    }\n}\n",
        )

        assertEquals(
            "namespace App;\n\nfinal class B<T of object>\n{\n    /** @param \$raw<caret> */\n    get(Id \$id, \$raw, list<int> \$xs, string|list<int> \$e, int ...\$n): ?T\n    {\n        return null;\n    }\n}\n",
            result,
        )
    }

    fun testATypedPropertyGetsNoVarTag() {
        val result = enter("C.phn", "namespace App;\n\nfinal class C\n{\n    /**<caret>\n    private list<int> \$xs;\n}\n")

        assertEquals("namespace App;\n\nfinal class C\n{\n    /** <caret> */\n    private list<int> \$xs;\n}\n", result)
    }

    fun testAThrowsClauseTakesTheThrowsTags() {
        myFixture.addFileToProject("Errors.phn", "namespace App;\n\nfinal class Boom extends \\Exception {\n}\n")
        val result = enter(
            "T.phn",
            "namespace App;\n\nfinal class T {\n    /**<caret>\n    go(int \$n, \$raw): void throws Boom {\n        throw new Boom();\n    }\n}\n",
        )

        assertEquals("namespace App;\n\nfinal class T {\n    /** @param \$raw<caret> */\n    go(int \$n, \$raw): void throws Boom {\n        throw new Boom();\n    }\n}\n", result)
    }

    fun testATagForAnExceptionTheClauseDoesNotNameStays() {
        // PhpStorm's own stub of `\Exception`: the test project has none.
        PhpLanguage::class.java.classLoader.getResource("stubs/Core/Core_c.php")?.readText()?.let { myFixture.addFileToProject("stubs/Core_c.php", it) }
        myFixture.addFileToProject("Errors.phn", "namespace App;\n\nfinal class Boom extends \\Exception {\n}\n\nfinal class Other extends \\Exception {\n}\n")
        val result = enter(
            "T.phn",
            "namespace App;\n\nfinal class T {\n    /**<caret>\n    go(int \$n, \$raw): void throws Boom {\n        if (\$n > 0) {\n            throw new Boom();\n        }\n        throw new Other();\n    }\n}\n",
        )

        assertTrue(result, result.contains("    /**\n     * @param \$raw") && result.contains("     * @throws Other") && !result.contains("@throws Boom"))
    }

    fun testAClassStubIsOneLine() {
        val result = enter("K.phn", "namespace App;\n\n/**<caret>\nfinal class K {\n}\n")

        assertEquals("namespace App;\n\n/** <caret> */\nfinal class K {\n}\n", result)
    }

    fun testADelegateThatPassesTheEnterOnIsAskedOnce() {
        // Nobody stops the Enter: the platform types it, and does not ask the delegates behind the plugin's again.
        val first = Recording(EnterHandlerDelegate.Result.Continue)
        val second = Recording(EnterHandlerDelegate.Result.Continue)
        round(first, second)

        val result = enter("A.phn", "namespace App;\n\nfinal class A\n{\n    /**<caret>\n    get(int \$id): int => \$id;\n}\n")

        assertEquals(1, first.asked)
        assertEquals(1, second.asked)
        assertEquals(1, first.after)
        assertTrue(result, result.startsWith("namespace App;\n\nfinal class A\n{\n    /**\n") && result.contains("<caret>"))
    }

    fun testADelegateThatStopsTheEnterWithoutAStubIsObeyed() {
        // Stopped before PhpStorm's delegate: no Enter, no stub, and nothing of the open comment is trimmed.
        val stopping = Recording(EnterHandlerDelegate.Result.Stop)
        val behind = Recording(EnterHandlerDelegate.Result.Continue)
        round(stopping, behind)
        val source = "namespace App;\n\nfinal class A\n{\n    /**<caret>\n    get(int \$id, \$raw): int => \$id;\n}\n"

        assertEquals(source, enter("A.phn", source))
        assertEquals(1, stopping.asked)
        assertEquals(0, behind.asked)
    }

    fun testADelegateThatAsksForTheDefaultEnterIsObeyed() {
        for (decision in listOf(EnterHandlerDelegate.Result.Default, EnterHandlerDelegate.Result.DefaultForceIndent, EnterHandlerDelegate.Result.DefaultSkipIndent)) {
            val deciding = Recording(decision)
            val behind = Recording(EnterHandlerDelegate.Result.Continue)
            val masked = Disposer.newDisposable(testRootDisposable, "the round of $decision")
            round(deciding, behind, until = masked)

            val result = enter("A.phn", "namespace App;\n\nfinal class A\n{\n    /**<caret>\n    get(int \$id): int => \$id;\n}\n")
            Disposer.dispose(masked)

            assertEquals("$decision", 1, deciding.asked)
            assertEquals("$decision", 0, behind.asked)
            assertTrue("$decision: $result", result.startsWith("namespace App;\n\nfinal class A\n{\n    /**\n"))
        }
    }

    fun testAPhpFileLeavesTheRoundToThePlatform() {
        val recording = Recording(EnterHandlerDelegate.Result.Continue)
        round(recording)

        enter("D.php", "<?php\nnamespace App;\n\nfinal class D\n{\n    /**<caret>\n    public function get(): int { return 1; }\n}\n")

        assertEquals(1, recording.asked)
    }

    /** The plugin's delegate, then [behind] it, as the whole of the platform's round. */
    private fun round(vararg behind: EnterHandlerDelegate, until: Disposable = testRootDisposable) {
        val own = EnterHandlerDelegate.EP_NAME.extensionList.single { it is PhaenonDocCommentEnterHandler }
        ExtensionTestUtil.maskExtensions(EnterHandlerDelegate.EP_NAME, listOf(own) + behind, until)
    }

    /** A delegate that answers [decision] and counts how often it was asked, before the Enter and after it. */
    private class Recording(private val decision: EnterHandlerDelegate.Result) : EnterHandlerDelegateAdapter() {
        var asked = 0
        var after = 0

        override fun preprocessEnter(
            file: PsiFile,
            editor: Editor,
            caretOffset: Ref<Int>,
            caretAdvance: Ref<Int>,
            dataContext: DataContext,
            originalHandler: EditorActionHandler?,
        ): EnterHandlerDelegate.Result {
            asked++
            return decision
        }

        override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
            after++
            return EnterHandlerDelegate.Result.Continue
        }
    }

    fun testAPhpFileKeepsPhpStormsStub() {
        val result = enter(
            "D.php",
            "<?php\nnamespace App;\n\nfinal class D\n{\n    /**<caret>\n    public function get(Id \$id, \$raw): ?object\n    {\n        return null;\n    }\n}\n",
        )

        assertTrue(result, result.contains("     * @param Id \$id\n     * @param \$raw\n     * @return null"))
    }
}
