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
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel.set(true)
    }
}
