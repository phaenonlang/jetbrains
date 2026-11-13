import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
// release-please bumps this line in its release PR (release-please-config.json); the tag is v<version>.
version = "0.6.1" // x-release-please-version

kotlin {
    // PhpStorm 2026.1+ runs on JBR 25, and its own classes are Java 21 bytecode: so are the plugin's.
    jvmToolchain(21)
    compilerOptions {
        // The plugin runs on the Kotlin standard library the IDE bundles, 2.3 in PhpStorm 2026.1.4;
        // the API version stays at 2.1, which everything here needs. A newer API version than the
        // library's lets the compiler call library functions that are not there, and it
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
        // instead, for behaviour only a newer PhpStorm has. The plugin itself is built against
        // platformVersion: that is what it supports.
        val testIde = providers.gradleProperty("testIdePath").orNull
        if (testIde != null) local(testIde) else phpstorm(providers.gradleProperty("platformVersion"))
        // The PHP plugin provides the lexer, parser and PSI we reuse; the TextMate plugin
        // hosts the highlighting-only fallback. Both are optional at runtime (see plugin.xml).
        // Grazie: since 2025.3 the typo inspection is its GrazieSpellCheckingInspection; the
        // test IDE has it only when the plugin is loaded. The plugin itself does not depend
        // on Grazie (plugin.xml declares what it needs).
        bundledPlugins("com.jetbrains.php", "org.jetbrains.plugins.textmate", "tanvd.grazi")
        // The spellchecker API is a content module of the platform since 2025.2, no longer in
        // the core jar the compiler sees by itself.
        bundledModule("intellij.spellchecker")
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
        // Fail on what breaks the plugin in an IDE of the range, or soon will (API scheduled for
        // removal). Internal API is reported and checked against verifier/internal-api.txt by
        // verifyInternalApi below, which fails on any use the list does not name: the uses listed
        // there have no public replacement (README, "Internal API").
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
        ides {
            // The PhpStorm build the plugin is compiled against (platformVersion), already on disk.
            current()
            // The newest build of every PhpStorm line in the declared range (sinceBuild up, EAP and
            // RC builds included): the range the Marketplace verifies an upload against.
            // -PverifyReleasesOnly=true leaves EAP and RC builds out: the release job's gate, which
            // an EAP marking something internal must not hold up (CI still sees it first).
            if (providers.gradleProperty("verifyReleasesOnly").orNull.toBoolean()) {
                select {
                    types = listOf(IntelliJPlatformType.PhpStorm)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                    sinceBuild = providers.gradleProperty("pluginSinceBuild")
                }
            } else {
                recommended()
            }
            // Optionally verify against an installed IDE as well:
            //   ./gradlew verifyPlugin -PlocalIdePath=/Applications/PhpStorm.app/Contents
            providers.gradleProperty("localIdePath").orNull?.let { local(file(it)) }
        }
    }
}

// The internal API uses of the Plugin Verifier's reports, one per used class and plugin class: "<API
// class> in <plugin class>", the API class being the class itself or the one declaring the member.
fun internalApiUses(report: File): Set<String> = report.readLines().filter { it.startsWith("Internal ") }.mapNotNull { line ->
    val match = Regex("""^Internal (class|interface|annotation|enum|method|constructor|field) (\S+).*? in (?:class )?dev\.phaenon\.jetbrains\.([A-Za-z0-9_]+)""").find(line)
        ?: return@mapNotNull "unparsed: $line"
    val (kind, element, user) = match.destructured
    val api = when (kind) {
        "class", "interface", "annotation", "enum" -> element
        else -> element.substringBefore('(').substringBeforeLast('.')
    }
    "$api in $user"
}.toSet()

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

// Fails when the Plugin Verifier saw internal API that verifier/internal-api.txt does not name, in any IDE it
// verified against, and when the list names a use no IDE reports. Runs after verifyPlugin, on the reports of
// this version.
val verifyInternalApi by tasks.registering {
    group = "verification"
    description = "Fails on internal API uses that verifier/internal-api.txt does not name, and on lines no IDE reports."
    val listed = layout.projectDirectory.file("verifier/internal-api.txt")
    val reports = layout.buildDirectory.dir("reports/pluginVerifier")
    val version = project.version.toString()
    val pluginDir = "plugins/dev.phaenon.jetbrains/$version"
    inputs.file(listed)
    doLast {
        val allowed = listed.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val ides = reports.get().asFile.listFiles().orEmpty().filter { it.resolve(pluginDir).isDirectory }.sortedBy { it.name }
        if (ides.isEmpty()) throw GradleException("No Plugin Verifier reports of $version under ${reports.get().asFile}: run verifyPlugin")
        val unlisted = ides.flatMap { ide ->
            val report = ide.resolve("$pluginDir/internal-api-usages.txt")
            val uses = if (report.isFile) internalApiUses(report) else emptySet()
            (uses - allowed).map { "${ide.name}: $it" }
        }
        if (unlisted.isNotEmpty()) {
            throw GradleException("Internal API that verifier/internal-api.txt does not name:\n  " + unlisted.joinToString("\n  "))
        }
        // A line no IDE reports is a use that went, or a report the verifier no longer writes or
        // words differently: either way the list no longer says what the verifier saw.
        val used = ides.flatMap { ide -> ide.resolve("$pluginDir/internal-api-usages.txt").takeIf { it.isFile }?.let(::internalApiUses).orEmpty() }.toSet()
        val stale = allowed - used
        if (stale.isNotEmpty()) {
            throw GradleException("verifier/internal-api.txt names internal API no verified IDE reports:\n  " + stale.joinToString("\n  "))
        }
    }
}

tasks.withType<VerifyPluginTask>().configureEach {
    finalizedBy(verifyInternalApi)
}

// `./gradlew testInIde -PtestInIdePath=/Applications/PhpStorm.app/Contents` runs the suite inside that
// IDE with the plugin as platformVersion builds it: what the released plugin meets in a newer PhpStorm.
// (-PtestIdePath, above, builds against the IDE as well.)
providers.gradleProperty("testInIdePath").orNull?.let { path ->
    intellijPlatformTesting.testIde.register("testInIde") {
        localPath = file(path)
        plugins {
            bundledPlugins("com.jetbrains.php", "org.jetbrains.plugins.textmate", "tanvd.grazi")
            bundledModule("intellij.spellchecker")
        }
        testFramework(TestFrameworkType.Platform)
        // The task is given the test classes; their resources (linemap/vectors.json) come with the source set's output.
        task { classpath += sourceSets["test"].output }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // PhaenonRealTreeTest parses every .phn file under the directories named by PHAENON_TREES
    // (colon-separated) and skips when the variable is unset. Passed on as a system property, and
    // declared as an input so that setting it re-runs the task instead of hitting the build cache.
    val trees = providers.environmentVariable("PHAENON_TREES").orElse("")
    inputs.property("phaenonTrees", trees)
    systemProperty("phaenon.trees", trees.get())
    // PhaenonTreeEqualityTest compares, for every PHP file under the directories named by
    // PHAENON_PHP_TREES (colon-separated), the tree of the file read as a .phn with PhpStorm's own.
    val phpTrees = providers.environmentVariable("PHAENON_PHP_TREES").orElse("")
    inputs.property("phaenonPhpTrees", phpTrees)
    systemProperty("phaenon.phpTrees", phpTrees.get())
    // PhaenonXdebugTest drives a real Xdebug over DBGp: PHAENON_XDEBUG names the extension (loaded
    // with `php -n -d zend_extension=…` only), PHAENON_PHP the php binary (default `php`).
    val xdebug = providers.environmentVariable("PHAENON_XDEBUG").orElse("")
    val php = providers.environmentVariable("PHAENON_PHP").orElse("")
    inputs.property("phaenonXdebug", xdebug)
    inputs.property("phaenonPhp", php)
    systemProperty("phaenon.xdebug", xdebug.get())
    systemProperty("phaenon.php", php.get())
}
