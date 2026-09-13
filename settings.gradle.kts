// Toolchain auto-provisioning: this machine has JDK 17 and 25 but not 21, and
// CLAUDE.md section 5 fixes the language level at 21. The foojay resolver lets
// Gradle download a Temurin 21 toolchain itself rather than making the build
// depend on what happens to be installed. First build needs network; after
// that it is cached in the Gradle user home.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// Repositories are declared once, here, and subprojects are forbidden from
// adding their own. A dependency can then only come from somewhere this file
// names, which is a property worth having in a build that pulls in code at all.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "opsatlas"

include(":control-plane")
project(":control-plane").projectDir = file("apps/control-plane")
