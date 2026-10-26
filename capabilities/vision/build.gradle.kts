import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.autohost.capabilities.vision"
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

dependencies {
    implementation(project(":core:broker"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // P10-05 engines (ADR-072): both Apache-2.0, each behind a seam so the pure module core
    // stays JVM-testable and the engines are swappable at the spike boundary (S28).
    implementation(libs.mlkit.text.recognition) // bundled on-device OCR, no Play Services needed
    implementation(libs.zxing.core)             // QR decode

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:common")))
}
