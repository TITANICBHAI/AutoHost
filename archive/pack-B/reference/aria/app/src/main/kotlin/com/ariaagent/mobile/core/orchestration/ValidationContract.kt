// Ported from donors/orchestration-java/ValidationContract.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin interface + sealed-result-style data class with factories.

package com.ariaagent.mobile.core.orchestration

/**
 * Optional contract a component can provide to the orchestrator that lets the
 * orchestrator validate its outputs before they are forwarded downstream.
 */
fun interface ValidationContract {
    fun validate(componentId: String, output: Map<String, Any?>): Result

    data class Result(
        val isValid: Boolean,
        val message: String,
        val details: Map<String, Any?> = emptyMap(),
    ) {
        companion object {
            fun success(): Result = Result(true, "Validation passed")
            fun failure(message: String, details: Map<String, Any?> = emptyMap()): Result =
                Result(false, message, details)
        }
    }
}
