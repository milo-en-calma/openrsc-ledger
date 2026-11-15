plugins {
    // Lets Gradle download the Java 25 toolchain on a machine that doesn't have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "openrsc-ledger"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("core", "admin-server", "web")
