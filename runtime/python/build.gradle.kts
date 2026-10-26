import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "app.autohost.runtime.python"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk {
            // 64-bit phone ABI plus x86_64 for emulators. Chaquopy Python 3.13 is 64-bit only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("requests")
        }
    }
}

dependencies {
    api(project(":core:broker"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":runtime:api"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
