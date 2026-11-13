pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "anmp"

include(":format")
include(":core")

// ANDP Kotlin client from the neighbouring repository (ADR 0036): ../../ANDP relative to this folder,
// i.e. ANDP checked out next to ANMP, or ../../../ANDP for a worktree under _worktrees/. Override with
// -Pandp.dir=<path to ANDP/kotlin> or the environment variable ANDP_DIR (also when ANMP is an included build).
val andpDir = providers.gradleProperty("andp.dir").orNull
    ?: providers.environmentVariable("ANDP_DIR").orNull
    ?: listOf("../../ANDP/kotlin", "../../../ANDP/kotlin").firstOrNull { settingsDir.resolve(it).resolve("settings.gradle.kts").exists() }
    ?: "../../ANDP/kotlin"
includeBuild(andpDir) {
    // Both repositories keep their build in a folder `kotlin`; a name of its own keeps them apart in a composite.
    name = "andp"
    dependencySubstitution {
        substitute(module("app.anynear:andp")).using(project(":andp"))
    }
}
