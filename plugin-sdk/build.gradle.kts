import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// P11-02 (ADR-079): the plugin SDK — the AIDL contract plus a small dependency-free base service
// external plugin APKs extend. The host (:app) depends on this module for the binder interface;
// plugin projects include it too (see samples/plugin-example).
android {
    namespace = "app.autohost.plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
