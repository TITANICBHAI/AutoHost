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

rootProject.name = "autohost"

// Only modules that have a build file are included. Add each one as it is written (see HANDOFF.md section 4).
include(":core:model")
include(":core:common")
include(":core:broker")
include(":core:engine")
include(":core:data")
include(":core:devapi")
include(":runtime:api")
include(":runtime:python")
include(":runtime:flow")
include(":runtime:js") // P11-01: the JavaScript (QuickJS) runtime — pure JVM; the engine touchpoint lives in :app
include(":capabilities:a11y")
include(":capabilities:app")
include(":capabilities:ime")
include(":capabilities:screen")
include(":capabilities:projection")
include(":capabilities:notifications")
include(":capabilities:clipboard")
include(":capabilities:intents")
include(":capabilities:files")
include(":capabilities:audio")
include(":capabilities:net")
include(":capabilities:rawbridge")
include(":capabilities:shell") // P9-01..06: the shell tier (adb client, transports, device.shell/ui/screenshot providers)
include(":capabilities:browser") // P10-02..04: the browser tier (webview host, a11y link, CDP over the shell tier)
include(":capabilities:vision") // P10-05: OCR / template match / QR (engines behind seams, ADR-072)
include(":capabilities:data") // P10-06..08: personal data + hardware + sms/telephony (seam-backed, ADR-073/074)
include(":core:designsystem")
include(":plugin-sdk") // P11-02 (ADR-079): the AIDL contract + base service external plugin APKs extend
include(":app")
include(":testing:fixture-app")

// Planned, in build order (REPO_LAYOUT.md) — added when their phase starts:
//   ":core:data", ":core:devapi", ":runtime:flow",
//   ":capabilities:{ime,screen,projection,net,notify,prompt,events,files,intent,system,data,shell,vision,browser,rawbridge}",
//   ":feature:{home,library,taskdetail,profiles,runs,schedule,triggers,setup,settings,devtools,taskui,surfaces,inspector,recorder}",
//   ":plugin-sdk", ":testing:fakes"
