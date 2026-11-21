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
        // INT-T4: the app's test step hung for the whole limit in CI runs 47, 48 and 49 (8, 8 and 15 minutes), while a normal
        // run takes about 5 minutes from start to digest, so that step is stuck, not slow. 8 minutes for every module again.
        timeout.set(java.time.Duration.ofMinutes(8))
        // Gradle starts a test JVM with 512 MB. The app's Robolectric + Compose journey tests keep a whole app alive per test;
        // a JVM that is nearly full does not fail, it collects garbage back to back and looks hung. The workflow now keeps a
        // thread dump and heap summary of every test JVM (jvm-tests.yml), so the next hang names its place.
        if (project.path == ":app") maxHeapSize = "2g"
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
