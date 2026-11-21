// Root build file: plugin versions come from gradle/libs.versions.toml. Modules apply what they need.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.chaquopy) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// CI failure details: every failing test prints its full exception into the Gradle log, which the
// ci.yml diagnostics grep (annotations are the only failure channel readable without admin access).
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // A hung test must fail by itself instead of eating the CI budget (C-02 and C-03 were silent hangs).
        // The app's journey tests (Robolectric + Compose, a whole app launch each) sit at the edge of 8 minutes: run 46
        // passed just under it, runs 47 (docs only) and 48 ran out of it with no single test shown to be
        // stuck (INT-T3). The app gets 15, every other module keeps 8.
        timeout.set(java.time.Duration.ofMinutes(if (project.path == ":app") 15L else 8L))
        testLogging {
            events("started", "failed") // "started" lets tools/ci_digest.py name the test that hung
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = false
        }
    }
}

// Lint is applied centrally to every module (ADR-030) because we deferred convention plugins.
// `gradle check` then runs unit tests + ktlintCheck + detekt in one command (AGENTS.md §6).
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // ktlint reads the root .editorconfig for per-project tweaks (line length, imports).
        android.set(true)
        outputToConsole.set(true)
        ignoreFailures.set(false)
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }
}
