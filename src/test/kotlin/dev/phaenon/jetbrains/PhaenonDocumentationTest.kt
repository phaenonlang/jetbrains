package dev.phaenon.jetbrains

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.PhpLanguageLevel
import com.jetbrains.php.config.PhpProjectConfigurationFacade

/**
 * [PhaenonDocumentationProvider]: the definition line of quick documentation and the Ctrl+hover
 * hint in Phaenon's spelling (`testData/docs/Types.phn`). A declaration prints its type as
 * written; a variable prints the type PhpStorm holds through [PhaenonTypePresentation]; what holds
 * nothing Phaenon-specific is PhpStorm's. The class-string cases are in [PhaenonClassStringTest].
 */
class PhaenonDocumentationTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        PhpProjectConfigurationFacade.getInstance(project).languageLevel = PhpLanguageLevel.values().last()
        myFixture.configureByFiles("docs/Types.phn")
    }

    fun testADeclarationPrintsItsTypeAsWritten() {
        assertEquals("\$users : list<User>", myFixture.hint("\$users,", 1))
        assertEquals("\$maybe : ?class<User>", myFixture.hint("\$maybe,", 1))
        assertEquals("\$format : Closure(int): string", myFixture.hint("\$format,", 1))
    }

    fun testAVariablePrintsTheTypePhpStormHoldsInPhaenonsSpelling() {
        assertEquals("list", "\$list : list<User>", myFixture.hint("\$list =", 1))
        assertEquals("map", "\$map : map<string, User>", myFixture.hint("\$map =", 1))
        assertEquals("bytes", "\$bytes : bytes", myFixture.hint("\$bytes =", 1))
        assertEquals("nullable class-string", "\$nullable : ?class<User>", myFixture.hint("\$nullable =", 1))
        assertEquals("closure signature", "\$closure : Closure(int): string", myFixture.hint("\$closure =", 1))
    }

    fun testTheStrMethodsOfAReceiverAreNotPartOfItsType() {
        // `$name->upper()` gives the receiver the pseudo-class StrMethods; it is how the method
        // syntax resolves, not a type of `$name`.
        assertEquals("\$name : string", myFixture.hint("\$name->upper", 1))
    }

    fun testAVariableWithoutPhaenonTypesIsPhpStorms() {
        assertEquals("\$plain : string", myFixture.hint("\$plain =", 1))
    }

    fun testAFunctionPrintsItsSignatureAsWritten() {
        val signature = "protected static fun pick<T of User>(list<T> \$from, int \$limit = 3, string ...\$tags): ?T"
        assertEquals(signature, myFixture.hint("fun pick", 5))
        val doc = myFixture.quickDoc("fun pick", 5)
        // One parameter per line in the popup, as PhpStorm lays a signature out; the docblock sections stay PhpStorm's.
        assertTrue(doc, doc.contains("protected static fun pick<T of User>( list<T> \$from, int \$limit = 3, string ...\$tags ): ?T"))
        assertTrue(doc, doc.contains("how many"))
    }
}
