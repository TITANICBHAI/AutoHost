import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// P13-10..15 (ADR-086, docs/AGENT_RUNTIME.md §3): the agent runtime — PURE JVM.
// May depend on :runtime:api, :core:model, :core:common, :core:llm — NEVER on sibling runtimes,
// :app or any Android API. The one action path is BrokerTransport (a seam; :app wires it).

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":runtime:api"))
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:llm"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
