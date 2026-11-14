plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Werkzeuge der JS-Tests (kotlin-js-store/yarn.lock) auf Versionen ohne bekannte Lücken festlegen (ADR 0102).
// Renovate pflegt diese Datei nicht; nach einer Änderung hier: ./gradlew kotlinUpgradeYarnLock
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("ws", "8.21.0")
        resolution("serialize-javascript", "7.1.3")
        resolution("follow-redirects", "1.16.2")
        resolution("glob", "14.0.2")
        resolution("minimatch", "11.0.1")
        resolution("diff", "8.0.3")
        resolution("webpack", "5.104.1")
    }
}

// Abhängigkeiten festhalten (gradle.lockfile je Projekt), damit der Schwachstellen-Scan auch indirekte Abhängigkeiten
// sieht und Builds wiederholbar sind (ADR 0102). LENIENT: fehlende Einträge brechen den Build nicht ab.
// Neu schreiben: ./gradlew resolveAndLockAll --write-locks
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.LENIENT)
    }
}

allprojects {
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("löst alle Konfigurationen auf")
        doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "nur mit --write-locks aufrufen" } }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { c ->
                runCatching { c.resolvedConfiguration.lenientConfiguration.artifacts }
                    .onFailure { logger.warn("${project.path}:${c.name} nicht auflösbar: ${it.message?.lineSequence()?.first()}") }
            }
        }
    }
}
