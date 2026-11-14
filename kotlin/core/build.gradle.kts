// :core — ANMP core on ANDP (ADR 0035, 0036): circles, contacts, personal mailbox, sending, fetching,
// receipts, history, key changes and forwarding, without UI and without platform code. The app
// plugs in storage, clock and push through the interfaces in Platform.kt.
//
//   ./gradlew :core:jvmTest :core:jsNodeTest :core:testAndroidHostTest
//   ./gradlew :core:compileKotlinIosSimulatorArm64
//
// jvmTest also runs against a Go test relay (repository ANDP-Peer, next to ANDP, or ANDP_PEER_DIR)
// when it and `go` are present; ANMP_REQUIRE_GO=1 makes a missing one an error.

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Same lookup as settings.gradle.kts.
val andpKotlinDir = providers.gradleProperty("andp.dir").orNull
    ?: providers.environmentVariable("ANDP_DIR").orNull
    ?: listOf("../../ANDP/kotlin", "../../../ANDP/kotlin").firstOrNull { rootDir.resolve(it).resolve("settings.gradle.kts").exists() }
    ?: "../../ANDP/kotlin"
val andpGoDir = providers.environmentVariable("ANDP_PEER_DIR").map { rootProject.layout.projectDirectory.dir(it) }
    .getOrElse(rootProject.layout.projectDirectory.dir(andpKotlinDir).dir("../../ANDP-Peer"))

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "app.anynear.anmp.core"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    js {
        outputModuleName.set("anmp-core")
        // ES modules, so that bundlers like Vite (web client, ADR 0036) take the library as it is.
        useEsModules()
        compilerOptions { freeCompilerArgs.add("-Xes-long-as-bigint") }
        browser {
            testTask { enabled = false } // no browser in CI; the same code runs under Node
        }
        nodejs {
            testTask { useMocha { timeout = "120s" } }
        }
        binaries.library()
        generateTypeScriptDefinitions()
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // Pinned TLS to relay IP addresses (ANDP §12): Android, JVM, iOS. JS uses Web PKI.
            group("pinned") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
                group("ios") { withIos() }
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        optIn.add("kotlin.js.ExperimentalJsExport")
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":format"))
            api("app.anynear:andp")
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xes-long-as-bigint")
}

// Radio list vectors (vectors/anmp-v1-radio-list.json): UPDATE_VECTORS=1 ./gradlew :core:jvmTest rewrites the file.
val radioListVectors = rootProject.layout.projectDirectory.file("../vectors/anmp-v1-radio-list.json")

tasks.named<Test>("jvmTest") {
    val update = providers.environmentVariable("UPDATE_VECTORS").orElse("")
    inputs.property("updateVectors", update)
    inputs.files(radioListVectors).optional()
    systemProperty("anmp.radioListVectors", radioListVectors.asFile.absolutePath)
    systemProperty("anmp.updateVectors", update.get())
    environment("ANDP_GO_DIR", andpGoDir.asFile.absolutePath)
    environment("ANMP_REQUIRE_GO", providers.environmentVariable("ANMP_REQUIRE_GO").orElse("").get())
    inputs.files(fileTree(andpGoDir) { include("**/*.go", "go.mod", "go.sum") })
}
