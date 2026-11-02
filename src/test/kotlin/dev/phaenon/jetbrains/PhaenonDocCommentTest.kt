package dev.phaenon.jetbrains

import com.intellij.application.options.CodeStyle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.lang.formatter.ui.predefinedStyle.PSR12CodeStyle

/**
 * Enter after the opening of a docblock in a `.phn` file ([PhaenonDocCommentEnterHandler]): the
 * stub is PhpStorm's, aligned by the formatter, without a `@param` for a parameter that has a type,
 * a `@return` for a declared return type, or a `@var` for a typed property — erased types included,
 * which PhpStorm took for no type (`@return mixed` over `class<T>`). A `.php` file keeps PhpStorm's.
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
            "namespace App;\n\nabstract class A<T of object>\n{\n    /**<caret>\n    abstract protected static fun aggregateClass(): class<T>;\n}\n",
        )

        assertEquals(
            "namespace App;\n\nabstract class A<T of object>\n{\n    /**\n     * <caret>\n     */\n    abstract protected static fun aggregateClass(): class<T>;\n}\n",
            result,
        )
    }

    fun testOnlyAnUntypedParameterKeepsItsTag() {
        val result = enter(
            "B.phn",
            "namespace App;\n\nfinal class B<T of object>\n{\n    /**<caret>\n    fun get(Id \$id, \$raw, list<int> \$xs, string|list<int> \$e, int ...\$n): ?T\n    {\n        return null;\n    }\n}\n",
        )

        assertEquals(
            "namespace App;\n\nfinal class B<T of object>\n{\n    /**\n     * @param \$raw<caret>\n     */\n    fun get(Id \$id, \$raw, list<int> \$xs, string|list<int> \$e, int ...\$n): ?T\n    {\n        return null;\n    }\n}\n",
            result,
        )
    }

    fun testATypedPropertyGetsNoVarTag() {
        val result = enter("C.phn", "namespace App;\n\nfinal class C\n{\n    /**<caret>\n    private list<int> \$xs;\n}\n")

        assertEquals("namespace App;\n\nfinal class C\n{\n    /**\n     * <caret>\n     */\n    private list<int> \$xs;\n}\n", result)
    }

    fun testAPhpFileKeepsPhpStormsStub() {
        val result = enter(
            "D.php",
            "<?php\nnamespace App;\n\nfinal class D\n{\n    /**<caret>\n    public function get(Id \$id, \$raw): ?object\n    {\n        return null;\n    }\n}\n",
        )

        assertTrue(result, result.contains("     * @param Id \$id\n     * @param \$raw\n     * @return null"))
    }
}
