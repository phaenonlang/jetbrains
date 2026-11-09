package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
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

    fun testAPhpFileKeepsPhpStormsStub() {
        val result = enter(
            "D.php",
            "<?php\nnamespace App;\n\nfinal class D\n{\n    /**<caret>\n    public function get(Id \$id, \$raw): ?object\n    {\n        return null;\n    }\n}\n",
        )

        assertTrue(result, result.contains("     * @param Id \$id\n     * @param \$raw\n     * @return null"))
    }
}
