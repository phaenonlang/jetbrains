rootProject.name = "phaenon-jetbrains"

plugins {
    // Lets Gradle provision a JDK 21 toolchain if none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
