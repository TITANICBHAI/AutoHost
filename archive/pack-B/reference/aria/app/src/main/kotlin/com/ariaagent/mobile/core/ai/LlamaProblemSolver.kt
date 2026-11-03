package com.ariaagent.mobile.core.ai

import android.util.Log
import com.ariaagent.mobile.core.orchestration.ProblemSolvingBroker

/**
 * Adapter that lets [com.ariaagent.mobile.core.orchestration.ProblemSolvingBroker]
 * use the on-device [LlamaEngine] to diagnose component problems.
 *
 * The donor used a cloud `GroqApiService` for this role — aria-prime is
 * on-device-only, so the broker takes a generic
 * [ProblemSolvingBroker.ProblemSolver] interface instead and we plug in the
 * local LLM here.
 *
 * Behaviour:
 *   • When the GGUF model is loaded, [solve] runs `infer()` and returns the
 *     generated text.
 *   • When the model is **not** loaded, [solve] throws — the broker catches
 *     the throwable, marks the ticket FAILED, and escalates it. That is the
 *     right outcome: a problem cannot be diagnosed by an LLM that isn't
 *     available, so escalation (currently a no-op pending ErrorResolutionWorkflow)
 *     is the honest path.
 *
 * Tuning notes:
 *   • [maxTokens] defaults to 256 — enough for a 3-section diagnostic answer
 *     (root cause / recommended solution / preventive measures) without
 *     wasting KV-cache budget on rambling.
 *   • [temperature] defaults to 0.3 — diagnostics should be conservative and
 *     repeatable, not creative.
 *   • Because [LlamaEngine.infer] already serialises through its own mutex,
 *     no additional locking is needed here. The broker's own [Semaphore] caps
 *     concurrent diagnoses at 3 anyway, so even with a fast LLM the queue
 *     stays bounded.
 *
 * Usage:
 * ```kotlin
 * val orchestrator = CentralOrchestrator(
 *     context = appContext,
 *     problemSolver = LlamaProblemSolver(),
 * )
 * ```
 */
class LlamaProblemSolver(
    private val maxTokens: Int = 256,
    private val temperature: Float = 0.3f,
) : ProblemSolvingBroker.ProblemSolver {

    override suspend fun solve(prompt: String): String {
        if (!LlamaEngine.isLoaded()) {
            Log.w(TAG, "LLM not loaded — cannot diagnose problem; ticket will be escalated")
            throw IllegalStateException("LlamaEngine model not loaded — cannot diagnose problem.")
        }
        Log.d(TAG, "Diagnosing problem with on-device LLM (prompt=${prompt.length} chars)")
        return LlamaEngine.infer(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            onToken = null,
        )
    }

    companion object {
        private const val TAG = "LlamaProblemSolver"
    }
}
