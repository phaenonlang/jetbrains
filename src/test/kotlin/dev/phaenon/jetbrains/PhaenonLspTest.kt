package dev.phaenon.jetbrains

import com.google.gson.JsonParser
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * `phn lsp` in PhpStorm ([PhaenonLspIntegrationProvider]): the project's compiler, asked for
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

    fun testTheProviderIsRegisteredWhereThePlatformLooksForIt() {
        // platform.lsp.integrationProvider (phaenon-lsp.xml), the extension point of the current
        // API, which PhpStorm declares from 2026.1.4 on.
        val point = ExtensionPointName.create<LspIntegrationProvider>("com.intellij.platform.lsp.integrationProvider")
        assertTrue(point.extensionList.any { it is PhaenonLspIntegrationProvider })
        // Loaded, the provider installs the restart the settings page asks for after a change: its
        // servers stopped and started again through LspClientManager.
        PhaenonRestart.languageServer(project)
    }

    fun testTheServerIsTheProjectsCompilerAskedForDiagnosticsOnly() {
        val phn = myFixture.configureByText("A.phn", "namespace App;\n").virtualFile
        val php = myFixture.configureByText("B.php", "<?php\n").virtualFile
        val root = phn.parent
        val descriptor = PhaenonLspClientDescriptor(project, root, "/opt/phn/bin/phn")

        val command = descriptor.createCommandLine()
        assertEquals("/opt/phn/bin/phn", command.exePath)
        assertEquals(listOf("lsp"), command.parametersList.list)
        assertEquals(root.path, command.workDirectory?.path)
        assertEquals(mapOf("features" to "diagnostics", "buildOnSave" to true), descriptor.createInitializationOptions())
        assertTrue(descriptor.isSupportedFile(phn))
        assertFalse("a .php is PhpStorm's alone", descriptor.isSupportedFile(php))
        assertEquals("phaenon", descriptor.getLanguageId(phn))
        // What the PSI answers is not asked of the server.
        val customization = descriptor.lspCustomization
        assertSame(LspGoToDefinitionDisabled, customization.goToDefinitionCustomizer)
        assertSame(LspHoverDisabled, customization.hoverCustomizer)
        assertSame(LspCompletionDisabled, customization.completionCustomizer)
        assertSame(LspFormattingDisabled, customization.formattingCustomizer)
        assertSame(LspSemanticTokensDisabled, customization.semanticTokensCustomizer)

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

    fun testEachServerTakesItsOwnProjectsFiles() {
        myFixture.addFileToProject("app/phaenon.json", "{\"roots\": {\"src\": \".src\"}}")
        myFixture.addFileToProject("app/composer.json", "{}")
        val own = myFixture.addFileToProject("app/src/Shop.phn", "namespace App;\n").virtualFile
        myFixture.addFileToProject("app/packages/inner/phaenon.json", "{\"roots\": {\"src\": \".src\"}}")
        val nested = myFixture.addFileToProject("app/packages/inner/src/Inner.phn", "namespace Inner;\n").virtualFile
        val app = PhaenonLspIntegrationProvider.serverRoot(project, own)!!
        val inner = PhaenonLspIntegrationProvider.serverRoot(project, nested)!!
        assertEquals("app", app.name)
        assertEquals("inner", inner.name)

        val outer = PhaenonLspClientDescriptor(project, app, "/opt/phn/bin/phn")
        assertTrue(outer.isSupportedFile(own))
        assertFalse("a nested project's file is its own server's", outer.isSupportedFile(nested))
        assertTrue(PhaenonLspClientDescriptor(project, inner, "/opt/phn/bin/phn").isSupportedFile(nested))
    }

    fun testInstalledCodeHasNoServer() {
        myFixture.addFileToProject("app/phaenon.json", "{\"roots\": {\"src\": \".src\"}}")
        myFixture.addFileToProject("app/composer.json", "{}")
        val own = myFixture.addFileToProject("app/src/Shop.phn", "namespace App;\n").virtualFile
        myFixture.addFileToProject("app/vendor/acme/lib/phaenon.json", "{\"roots\": {\"src\": \".src\"}}")
        myFixture.addFileToProject("app/vendor/acme/lib/composer.json", "{}")
        val installed = myFixture.addFileToProject("app/vendor/acme/lib/src/Lib.phn", "namespace Acme;\n").virtualFile
        // A directory named vendor is installed code only beside a composer.json.
        val notInstalled = myFixture.addFileToProject("app/src/vendor/Seller.phn", "namespace App;\n").virtualFile

        assertTrue(PhaenonCompiler.isVendorCode(installed))
        assertFalse(PhaenonCompiler.isVendorCode(own))
        assertFalse(PhaenonCompiler.isVendorCode(notInstalled))
        assertNull(PhaenonLspIntegrationProvider.serverRoot(project, installed))
        val app = PhaenonLspIntegrationProvider.serverRoot(project, own)!!
        assertEquals(app, PhaenonLspIntegrationProvider.serverRoot(project, notInstalled))
        assertFalse(
            "the project reads it as vendor code",
            PhaenonLspClientDescriptor(project, app, "/opt/phn/bin/phn").isSupportedFile(installed),
        )
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
