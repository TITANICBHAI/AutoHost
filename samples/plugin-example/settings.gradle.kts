// P11-02 (ADR-079): a standalone example — a capability-plugin APK the host discovers and binds.
// This is NOT part of the host's settings.gradle.kts: build it from this directory
//   (gradle :app:assembleDebug) and install the APK next to AutoHost.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "autohost-plugin-example"
include(":plugin-sdk")
// The shared plugin SDK lives in the host repo, one level up.
project(":plugin-sdk").projectDir = file("../../plugin-sdk")
include(":app")
