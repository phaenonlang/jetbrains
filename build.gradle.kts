import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
// release-please bumps this line in its release PR (release-please-config.json); the tag is v<version>.
version = "0.2.1" // x-release-please-version

kotlin {
    // PhpStorm 2025.1+ runs on JBR 21, so the plugin targets Java 21 bytecode.
    jvmToolchain(21)
    compilerOptions {
        // The plugin runs on the Kotlin standard library the IDE bundles, 2.1 in PhpStorm 2025.1.
        // A newer API version lets the compiler call library functions that are not there, and it
        // writes coroutine debug metadata that library rejects: with the IDE's coroutine debug
        // probes installed, a suspended coroutine of the plugin then never resumes.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        // No DefaultImpls bridges: in the compatibility modes the compiler adds, to a class
        // implementing a platform interface, an override of each default method it inherits,
        // which calls the interface's default. For a deprecated default (StatusBarWidget's
        // getPresentation(PlatformType)) the bridge is a usage of deprecated API the Plugin
        // Verifier reports. Nothing compiled outside this plugin calls its interfaces.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // `-PtestIdePath=/Applications/PhpStorm.app/Contents` builds and tests against an installed IDE
        // instead, for behaviour only a newer PhpStorm has (the PHP 8.5 pipe operator and its
        // inspections). The plugin itself is built against platformVersion: that is what it supports.
        val testIde = providers.gradleProperty("testIdePath").orNull
        if (testIde != null) local(testIde) else phpstorm(providers.gradleProperty("platformVersion"))
        // The PHP plugin provides the lexer, parser and PSI we reuse; the TextMate plugin
        // hosts the highlighting-only fallback. Both are optional at runtime (see plugin.xml).
        bundledPlugins("com.jetbrains.php", "org.jetbrains.plugins.textmate")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.phaenon.jetbrains"
        name = "Phaenon"
        version = project.version.toString()
        // The Marketplace's change notes are CHANGELOG.md's newest entry, which release-please writes
        // from the Conventional Commits of the release; plugin.xml carries none.
        changeNotes = provider { changeNotesFromChangelog() }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin only uses stable PHP plugin APIs.
            untilBuild = provider { null }
        }
        vendor {
            name = "Phaenon"
            url = "https://phaenon.dev"
        }
    }

    publishing {
        // publishPlugin uploads the zip to the JetBrains Marketplace (.github/workflows/release.yml);
        // the token is a Marketplace token of the vendor. The first version of a plugin is uploaded by
        // hand on plugins.jetbrains.com; the token publishes the versions after it.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        // Fail on real compatibility problems only. The one internal API we use
        // (com.jetbrains.php.baseLexerProvider) has no public alternative; see README.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
        ides {
            // The PhpStorm build the plugin is compiled against (platformVersion), already on disk.
            current()
            // Optionally verify against an installed IDE as well:
            //   ./gradlew verifyPlugin -PlocalIdePath=/Applications/PhpStorm.app/Contents
            providers.gradleProperty("localIdePath").orNull?.let { local(file(it)) }
        }
    }
}

// CHANGELOG.md's newest entry (from its first "## " heading to the next) as the HTML the Marketplace
// shows: release-please's Markdown is headings, bullets of "**scope:** subject ([sha](url))" and
// paragraphs, which is all this renders.
fun changeNotesFromChangelog(): String {
    val lines = layout.projectDirectory.file("CHANGELOG.md").asFile.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val next = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
    val entry = lines.subList(start, if (next < 0) lines.size else start + 1 + next)
    fun inline(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
        .replace(Regex("""\[([^\]]+)]\(([^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
    val html = StringBuilder()
    var item: StringBuilder? = null
    var paragraph: StringBuilder? = null
    fun flush() {
        item?.let { html.append("<li>").append(inline(it.toString().trim())).append("</li>\n"); item = null }
        paragraph?.let { html.append("<p>").append(inline(it.toString().trim())).append("</p>\n"); paragraph = null }
    }
    for (line in entry) {
        when {
            line.startsWith("## ") -> { flush(); html.append("<h3>").append(inline(line.removePrefix("## "))).append("</h3>\n") }
            line.startsWith("### ") -> { flush(); html.append("<h4>").append(inline(line.removePrefix("### "))).append("</h4>\n") }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flush()
                if (!html.endsWith("</li>\n")) html.append("<ul>\n")
                item = StringBuilder(line.substring(2))
            }
            line.isBlank() -> { flush(); if (html.endsWith("</li>\n")) html.append("</ul>\n") }
            item != null -> item!!.append(' ').append(line.trim())
            else -> (paragraph ?: StringBuilder().also { paragraph = it }).append(' ').append(line.trim())
        }
    }
    flush()
    if (html.endsWith("</li>\n")) html.append("</ul>\n")
    return html.toString()
}

// The TextMate bundle must exist as plain files on disk (the TextMate plugin reads a directory,
// not classpath resources), so it is copied next to the plugin jar in every sandbox and in the
// distribution zip produced by buildPlugin.
tasks.withType<PrepareSandboxTask>().configureEach {
    from(layout.projectDirectory.dir("textmate")) {
        into(pluginName.map { "$it/textmate" })
    }
}

tasks.test {
    useJUnit()
    // PhaenonRealTreeTest parses every .phn file under the directories named by PHAENON_TREES
    // (colon-separated) and skips when the variable is unset. Passed on as a system property, and
    // declared as an input so that setting it re-runs the task instead of hitting the build cache.
    val trees = providers.environmentVariable("PHAENON_TREES").orElse("")
    inputs.property("phaenonTrees", trees)
    systemProperty("phaenon.trees", trees.get())
    // PhaenonXdebugTest drives a real Xdebug over DBGp: PHAENON_XDEBUG names the extension (loaded
    // with `php -n -d zend_extension=…` only), PHAENON_PHP the php binary (default `php`).
    val xdebug = providers.environmentVariable("PHAENON_XDEBUG").orElse("")
    val php = providers.environmentVariable("PHAENON_PHP").orElse("")
    inputs.property("phaenonXdebug", xdebug)
    inputs.property("phaenonPhp", php)
    systemProperty("phaenon.xdebug", xdebug.get())
    systemProperty("phaenon.php", php.get())
}
