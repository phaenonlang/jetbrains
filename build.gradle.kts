import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    // PhpStorm 2025.1+ runs on JBR 21, so the plugin targets Java 21 bytecode.
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        phpstorm(providers.gradleProperty("platformVersion"))
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
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin only uses stable PHP plugin APIs.
            untilBuild = provider { null }
        }
        vendor {
            name = "Phaenon"
            url = "https://github.com/phaenonlang"
        }
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
}
