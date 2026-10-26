import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.autohost.core.engine"
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
    api(project(":core:model"))
    api(project(":runtime:api"))
    implementation(project(":core:broker"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    api(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:common")))
}
