package dev.phaenon.jetbrains

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase

/**
 * The reader against the format's reference vectors: `src/test/resources/linemap/vectors.json` is a
 * verbatim copy of the compiler's `internal/linemap/testdata/vectors.json` (the compiler's
 * freshness test fails when the copies differ). Every valid map gives its source and answers its
 * forward and reverse queries; every unsupported map is a version the reader leaves alone; every
 * invalid one is rejected.
 */
class PhaenonLineMapTest : TestCase() {

    private val vectors by lazy {
        val stream = javaClass.getResourceAsStream("/linemap/vectors.json") ?: error("vectors.json is not on the test classpath")
        JsonParser.parseString(stream.reader(Charsets.UTF_8).readText()).asJsonObject
    }

    private fun parse(line: String): PhaenonLineMap {
        val parsed = PhaenonLineMap.parse(line)
        assertTrue("not a valid map: $line ($parsed)", parsed is PhaenonLineMap.Parsed.Map)
        return (parsed as PhaenonLineMap.Parsed.Map).map
    }

    private fun pairs(array: JsonArray): List<Pair<Int, JsonElement>> = array.map { it.asJsonArray.let { p -> p[0].asInt to p[1] } }

    fun testValidVectors() {
        val valid = vectors.getAsJsonArray("valid")
        assertTrue(valid.size() > 0)
        for (vector in valid.map { it.asJsonObject }) {
            val name = vector["name"].asString
            val map = parse(vector["map"].asString)
            assertEquals(name, vector["source"].asString, map.source)
            for ((line, expected) in pairs(vector.getAsJsonArray("forward"))) {
                assertEquals("$name: forward $line", expected.asInt, map.forward(line))
            }
            for ((line, expected) in pairs(vector.getAsJsonArray("reverse"))) {
                assertEquals("$name: reverse $line", if (expected.isJsonNull) null else expected.asInt, map.reverse(line))
            }
        }
    }

    fun testUnsupportedVersionsAreNoMapping() {
        for (line in vectors.getAsJsonArray("unsupported").map { it.asString }) {
            assertEquals(line, PhaenonLineMap.Parsed.Unsupported, PhaenonLineMap.parse(line))
        }
    }

    fun testInvalidMapsAreRejected() {
        val invalid = vectors.getAsJsonArray("invalid").map { it.asString }
        assertTrue(invalid.isNotEmpty())
        for (line in invalid) assertEquals(line, PhaenonLineMap.Parsed.Invalid, PhaenonLineMap.parse(line))
    }

    fun testOtherLinesAreNoMap() {
        for (line in listOf("", "}", "// a comment", "//phaenon-map v1 Foo.phn 1:1", " // phaenon-map v1 Foo.phn 1:1")) {
            assertEquals(line, PhaenonLineMap.Parsed.Absent, PhaenonLineMap.parse(line))
        }
    }

    fun testOneTrailingCarriageReturnIsDropped() {
        assertEquals("Foo.phn", parse("// phaenon-map v1 Foo.phn 1:1\r").source)
        assertEquals(PhaenonLineMap.Parsed.Invalid, PhaenonLineMap.parse("// phaenon-map v1 Foo.phn 1:1\r\r"))
    }

    fun testHolds() {
        val map = parse("// phaenon-map v1 Foo.phn 1-6=1 7:1 15-17=9 18:9")
        assertEquals(listOf(true, true, false, false, true, true, false, false), listOf(1, 6, 7, 14, 15, 17, 18, 100).map(map::isHold))
    }

    fun testOnlyTheEscapesTheWriterUsesAreDecoded() {
        assertEquals(PhaenonLineMap.Parsed.Invalid, PhaenonLineMap.parse("// phaenon-map v1 a%2Fb.phn 1:1"))
        assertEquals("a b%.phn", parse("// phaenon-map v1 a%20b%25.phn 1:1").source)
    }

    /** The map is the file's last line, after a final line break or not; with `__halt_compiler()`, the line before it. */
    fun testReadingAFile() {
        val body = "<?php\n\nnamespace A;\n"
        val map = "// phaenon-map v1 A.phn 1-2=1 3:1"
        for (text in listOf(body + map, body + map + "\n", (body + map + "\n").replace("\n", "\r\n"))) {
            assertTrue(text, PhaenonLineMap.read(text) is PhaenonLineMap.Parsed.Map)
        }
        // The last line of a file ending in an empty line is that empty line.
        assertEquals(PhaenonLineMap.Parsed.Absent, PhaenonLineMap.read(body + map + "\n\n"))
        assertEquals(PhaenonLineMap.Parsed.Absent, PhaenonLineMap.read(body + "echo 1;\n"))
        assertEquals(PhaenonLineMap.Parsed.Absent, PhaenonLineMap.read(map + "\necho 1;\n"))
        val halted = "<?php\n\$x = 1;\n$map\n__halt_compiler();\u0000binary // phaenon-map v1 B.phn 1:1\n"
        assertEquals("A.phn", (PhaenonLineMap.read(halted) as PhaenonLineMap.Parsed.Map).map.source)
    }
}

/**
 * The two directions between a compiled file and its source in a project: through `phaenon.json`'s
 * roots, beside the source without one, and never to a compiled file whose map names another source.
 */
class PhaenonLineMapsTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    fun testThroughTheProjectFile() {
        myFixture.copyDirectoryToProject("linemap/project", "app")
        val source = myFixture.findFileInTempDir("app/src/Domain/Greeter.phn")
        val compiled = myFixture.findFileInTempDir("app/.src/Domain/Greeter.php")

        assertEquals(source, PhaenonLineMaps.sourceOf(compiled))
        assertEquals(compiled, PhaenonLineMaps.compiledOf(source))
        // Compiled line 14 (`return 'Hello, ' …`, 0-based 13) is a hold of source line 6 (0-based 5).
        assertEquals(PhaenonLineMaps.Position(source, 5), PhaenonLineMaps.forward(compiled, 13))
        assertTrue(PhaenonLineMaps.isHold(compiled, 13))
        // A breakpoint on that source line goes to the signature line, 12.
        assertEquals(PhaenonLineMaps.Position(compiled, 11), PhaenonLineMaps.reverse(source, 5))
        assertFalse(PhaenonLineMaps.isHold(compiled, 11))
        // `$a = $this->greet($name);`: source line 11, compiled line 24.
        assertEquals(PhaenonLineMaps.Position(compiled, 23), PhaenonLineMaps.reverse(source, 10))
        assertEquals(PhaenonLineMaps.Position(source, 10), PhaenonLineMaps.forward(compiled, 23))
    }

    fun testBesideTheSource() {
        myFixture.copyDirectoryToProject("linemap/beside", "lib")
        val source = myFixture.findFileInTempDir("lib/Note.phn")
        val compiled = myFixture.findFileInTempDir("lib/Note.php")

        assertEquals(compiled, PhaenonLineMaps.compiledOf(source))
        assertEquals(PhaenonLineMaps.Position(source, 4), PhaenonLineMaps.forward(compiled, 12))
        assertEquals(PhaenonLineMaps.Position(compiled, 10), PhaenonLineMaps.reverse(source, 4))
    }

    fun testAMapNamingAnotherSourceIsNoMapping() {
        myFixture.copyDirectoryToProject("linemap/project", "app")
        val stale = myFixture.findFileInTempDir("app/.src/Domain/Stale.php")
        assertNotNull(PhaenonLineMaps.of(stale))
        assertNull(PhaenonLineMaps.sourceOf(stale))
        assertNull(PhaenonLineMaps.forward(stale, 0))
    }

    fun testHandWrittenPhpHasNoMap() {
        val plain = myFixture.addFileToProject("plain/Plain.php", "<?php\n\necho 1;\n").virtualFile
        assertNull(PhaenonLineMaps.of(plain))
        assertNull(PhaenonLineMaps.forward(plain, 2))
    }

    fun testARebuiltFileIsReadAgain() {
        myFixture.copyDirectoryToProject("linemap/beside", "lib")
        val compiled = myFixture.findFileInTempDir("lib/Note.php")
        assertEquals(5, PhaenonLineMaps.of(compiled)!!.forward(12))
        val text = String(compiled.contentsToByteArray()).replace("12-14=5 15:6", "12-15=5 16:6")
        com.intellij.openapi.application.WriteAction.run<Exception> { com.intellij.openapi.vfs.VfsUtil.saveText(compiled, text) }
        assertTrue(PhaenonLineMaps.of(compiled)!!.isHold(15))
    }

    fun testTheMarkerIsReadFromTheHeader() {
        assertTrue(PhaenonFiles.hasMarkerInHeader("<?php\n\n/* @generated by phaenon from Foo.phn */\n\ndeclare(strict_types=1);\n"))
        assertTrue(PhaenonFiles.hasMarkerInHeader("<?php /* @generated by phaenon from Foo.phn */ declare(strict_types=1);\n"))
        assertFalse(PhaenonFiles.hasMarkerInHeader("<?php\n\nnamespace A;\n\n\n\n// @generated by phaenon, said a comment\n"))
    }

    fun testLexicalResolution() {
        assertEquals("/a/src/Foo.phn", PhaenonLineMaps.resolve("/a/.src", "../src/Foo.phn"))
        assertEquals("/a/b/Foo.phn", PhaenonLineMaps.resolve("/a/b", "./Foo.phn"))
        assertEquals("/Foo.phn", PhaenonLineMaps.resolve("/a", "../../Foo.phn"))
        assertEquals("C:/Foo.phn", PhaenonLineMaps.resolve("C:/a", "../../Foo.phn"))
        assertEquals("C:/p/src/Foo.phn", PhaenonLineMaps.resolve("C:/p/.src", "../src/Foo.phn"))
    }
}
