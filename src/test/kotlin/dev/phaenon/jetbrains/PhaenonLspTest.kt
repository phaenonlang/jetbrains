package dev.phaenon.jetbrains

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * `phn lsp` in PhpStorm ([PhaenonLspServerSupportProvider]): the project's compiler, asked for
 * diagnostics only, build on save as the settings say, its builds in the status bar.
 */
class PhaenonLspTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            PhaenonSettings.of(project).apply {
                binary = ""
                buildOnSave = true
            }
        } finally {
            super.tearDown()
        }
    }

    fun testTheServerIsTheProjectsCompilerAskedForDiagnosticsOnly() {
        val phn = myFixture.configureByText("A.phn", "namespace App;\n").virtualFile
        val php = myFixture.configureByText("B.php", "<?php\n").virtualFile
        val root = phn.parent
        val descriptor = PhaenonLspServerDescriptor(project, root, "/opt/phn/bin/phn")

        val command = descriptor.createCommandLine()
        assertEquals("/opt/phn/bin/phn", command.exePath)
        assertEquals(listOf("lsp"), command.parametersList.list)
        assertEquals(root.path, command.workDirectory?.path)
        assertEquals(mapOf("features" to "diagnostics", "buildOnSave" to true), descriptor.createInitializationOptions())
        assertTrue(descriptor.isSupportedFile(phn))
        assertFalse("a .php is PhpStorm's alone", descriptor.isSupportedFile(php))
        assertEquals("phaenon", descriptor.getLanguageId(phn))
        // What the PSI answers is not asked of the server.
        assertFalse(descriptor.lspGoToDefinitionSupport)
        assertFalse(descriptor.lspHoverSupport)
        assertNull(descriptor.lspCompletionSupport)
        assertNull(descriptor.lspFormattingSupport)

        PhaenonSettings.of(project).buildOnSave = false
        assertEquals(mapOf("features" to "diagnostics", "buildOnSave" to false), descriptor.createInitializationOptions())
    }

    fun testTheCompilerIsTheSettingElseTheProjectsVendorBin() {
        val dir = Files.createTempDirectory("phaenon-lsp").toFile()
        try {
            val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
            val launcher = File(dir, "vendor/bin/phn").apply {
                parentFile.mkdirs()
                writeText("#!/usr/bin/env php\n")
            }
            if (System.getenv("PHN_BINARY").isNullOrBlank()) {
                assertEquals(launcher.path, PhaenonCompiler.binary(project, root))
            }
            PhaenonSettings.of(project).binary = "/somewhere/phn"
            assertEquals("/somewhere/phn", PhaenonCompiler.binary(project, root))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testTheRootIsTheNearestProjectFile() {
        val nested = myFixture.addFileToProject("app/src/Domain/User.phn", "namespace App;\n").virtualFile
        myFixture.addFileToProject("app/phaenon.json", "{\"roots\": {\"src\": \".src\"}}")
        myFixture.addFileToProject("composer.json", "{}")
        assertEquals("app", PhaenonCompiler.root(project, nested)?.name)
    }

    fun testABuildOnSaveReadsInTheStatusBar() {
        fun build(json: String) = PhaenonBuild.of(JsonParser.parseString(json).asJsonObject)
        val clean = build("""{"files": 800, "written": 3, "withheld": 0, "durationMs": 1061}""")
        assertEquals("phn: built", clean.text)
        assertEquals("Last build on save: 800 files, 3 outputs written (1061 ms)", clean.tooltip)
        val withheld = build("""{"files": 800, "written": 0, "withheld": 2, "durationMs": 990}""")
        assertEquals("phn: 2 of 800 withheld", withheld.text)
        assertTrue(withheld.tooltip.contains("2 with errors not compiled"))
        val failed = build("""{"files": 0, "written": 0, "withheld": 0, "durationMs": 5, "error": "no phaenon.json: nothing to build"}""")
        assertEquals("phn: build failed", failed.text)

        PhaenonBuildStatus.of(project).update(withheld)
        assertEquals("phn: 2 of 800 withheld", PhaenonBuildWidget(project).getText())
    }
}
