import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.autohost"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.autohost"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions += "tier"
    productFlavors {
        create("core") {
            dimension = "tier"
            // a11y, notifications, net, files — no sensitive groups (docs/ANDROID_MANIFEST.md §1)
        }
        create("full") {
            dimension = "tier"
        }
    }

    // P11-09 (docs/RELEASE.md): the release keystore lives OUTSIDE the repo (plan §21-10) and is
    // injected by environment variables; absent credentials keep the unsigned release artifact
    // instead of failing the whole build (CI runs assembleCoreRelease unsigned, the owner signs).
    signingConfigs {
        create("release") {
            val storePath = System.getenv("AUTOHOST_KEYSTORE")
            val storePassword = System.getenv("AUTOHOST_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("AUTOHOST_KEY_ALIAS")
            val keyPassword = System.getenv("AUTOHOST_KEY_PASSWORD")
            if (storePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(storePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        // Same applicationId everywhere (ADR in HANDOFF §2): pm grant app.autohost … works on every build.
        debug {
        }
        release {
            isMinifyEnabled = false
            // Sign only when the credentials arrived; otherwise the artifact stays unsigned and
            // `docs/RELEASE.md` names the exact apksigner command for the owner's machine.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:broker"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:devapi"))
    implementation(project(":core:designsystem"))
    implementation(project(":runtime:api"))
    implementation(project(":runtime:python"))
    implementation(project(":runtime:flow")) // P6-06: flow runtime registration + P6-09/10 recorder & editor UI
    implementation(project(":runtime:js")) // P11-01: the JavaScript (QuickJS) runtime (engine touchpoint below)
    implementation(project(":plugin-sdk")) // P11-02: the capability-plugin binder contract (ADR-079)
    implementation(project(":capabilities:a11y"))
    implementation(project(":capabilities:app"))
    implementation(project(":capabilities:ime")) // AppGraph registers ImeProvider (first-build fix: was implicit)
    implementation(project(":capabilities:screen"))
    implementation(project(":capabilities:projection"))
    implementation(project(":capabilities:notifications")) // P8-01
    implementation(project(":capabilities:clipboard")) // P8-05
    implementation(project(":capabilities:intents")) // P8-06
    implementation(project(":capabilities:files")) // P8-07
    implementation(project(":capabilities:audio")) // P8-09 (device.audio + device.power)
    implementation(project(":capabilities:net")) // P8-08 (net.http gap + net.ws + net.server)
    implementation(project(":capabilities:rawbridge")) // P8-12 (android.api.direct)
    implementation(project(":capabilities:shell")) // P9-01..06 (shell tier transports + providers)
    implementation(project(":capabilities:browser")) // P10-02..04 (browser tier: webview/a11y/cdp)
    implementation(project(":capabilities:vision")) // P10-05 (OCR / template match / QR)
    implementation(project(":capabilities:data")) // P10-06..08 (contacts/calendar/media, hardware, sms/telephony)
    implementation(libs.shizuku.api) // P9-02: the Shizuku-backed Exec for ShizukuTransport (Apache-2.0)
    implementation(libs.quickjs.kt.android) // P11-02: the QuickJS engine for the JS runtime (Apache-2.0; owner-verify first build)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.glance.appwidget) // P7-09: home widget (plan §10.3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
