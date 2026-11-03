package com.ariaagent.mobile.core.rl

import android.util.Log
import com.ariaagent.mobile.core.ai.LlamaEngine
import com.ariaagent.mobile.core.memory.ExperienceStore

/**
 * LlmRewardEnricher — LLM-assisted reward rescoring for LoRA training quality.
 *
 * During idle training cycles, the base reward assigned by AgentLoop is coarse:
 *   +1.0 success, -0.5 failure, ±label_boost (binary from PixelVerifier).
 * Binary rewards are a weak training signal — a barely-passing gesture gets the
 * same weight as a precise, immediately-effective action.
 *
 * LlmRewardEnricher uses the already-loaded LlamaEngine to re-evaluate each
 * experience tuple and assign a continuous quality score in [0.0, 1.0].
 * That score is converted to a LoRA dataset weight:
 *   weight = 0.5 + enrichedReward × 1.5   →   range [0.5, 2.0]
 *
 * High-quality reasoning (clear goal → precise action → confirmed success)
 * gets double weight in the JSONL dataset, while low-quality actions are
 * down-weighted rather than excluded — we still learn from bad examples.
 *
 * Design choices:
 *   - Only runs when LlamaEngine.isLoaded() = true (avoids double RAM load).
 *   - MAX_TUPLES_PER_CYCLE = 20 caps thermal/time cost during the idle window.
 *   - maxTokens = 8 — the model only needs to output a decimal like "0.87".
 *   - Falls back to original reward silently on any inference error.
 *   - Runs on Dispatchers.Default (called from LearningScheduler coroutine).
 *   - Thread-safe: reads only from ExperienceTuple values, no shared state.
 *
 * Phase: Post-16 / Assessment
 */
object LlmRewardEnricher {

    private const val TAG                   = "LlmRewardEnricher"
    private const val MAX_TUPLES_PER_CYCLE  = 20
    private const val SCREEN_CHARS          = 200   // keep prompt short for 8-token output
    private const val ACTION_CHARS          = 120

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Enrich rewards for a list of experience tuples using LLM scoring.
     *
     * Called from LearningScheduler before LoraTrainer.train().
     * Returns an empty map if LlamaEngine is not loaded — training proceeds
     * with the original binary rewards in that case.
     *
     * @param tuples Full list of untraining-marked success tuples from ExperienceStore.
     *               Only the first [MAX_TUPLES_PER_CYCLE] are scored (thermal guard).
     * @return Map of tuple.id → enriched reward in [0.0, 1.0].
     *         Absent entries indicate the original reward should be used.
     */
    suspend fun enrich(tuples: List<ExperienceStore.ExperienceTuple>): Map<String, Double> {
        if (!LlamaEngine.isLoaded()) {
            Log.d(TAG, "LlamaEngine not loaded — skipping reward enrichment")
            return emptyMap()
        }

        val subset = tuples.take(MAX_TUPLES_PER_CYCLE)
        val enriched = mutableMapOf<String, Double>()

        Log.i(TAG, "Enriching rewards for ${subset.size} tuples via LLM…")

        for (tuple in subset) {
            try {
                val score = scoreOneTuple(tuple)
                if (score != null) {
                    enriched[tuple.id] = score
                    Log.d(TAG, "Tuple ${tuple.id.take(8)}: ${tuple.result} → enriched=%.3f".format(score))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scoring failed for ${tuple.id.take(8)}: ${e.message}")
            }
        }

        Log.i(TAG, "Enriched ${enriched.size}/${subset.size} rewards (avg=%.3f)".format(
            enriched.values.average().takeIf { enriched.isNotEmpty() } ?: 0.0
        ))
        return enriched
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private suspend fun scoreOneTuple(tuple: ExperienceStore.ExperienceTuple): Double? {
        val prompt = buildScoringPrompt(
            goal   = tuple.taskType.take(80),
            screen = tuple.screenSummary.take(SCREEN_CHARS),
            action = tuple.actionJson.take(ACTION_CHARS),
            result = tuple.result
        )
        val rawOutput = LlamaEngine.infer(prompt, maxTokens = 8)
        return parseScore(rawOutput)
    }

    /**
     * Model-agnostic scoring prompt — works with any instruction-tuned model
     * (Llama, Gemma, Qwen, SmolVLM, MiniCPM, etc.).
     *
     * No model-specific special tokens. Plain imperative text understood by all
     * instruction-tuned models. The model only needs to emit a decimal number,
     * which parseScore() extracts robustly regardless of surrounding whitespace.
     */
    private fun buildScoringPrompt(
        goal: String,
        screen: String,
        action: String,
        result: String
    ): String = """Rate the quality of this Android agent action from 0.0 to 1.0.
Reply with ONLY a single decimal number. No words, no explanation.

Goal: $goal
Screen: $screen
Action: $action
Result: $result

Score:""".trimIndent()

    /**
     * Extract the first valid float in [0.0, 1.0] from the LLM's raw output.
     *
     * Handles common model output patterns:
     *   "0.87"        → 0.87
     *   "0.87\n"      → 0.87
     *   " 0.87 points"→ 0.87
     *   "87%"         → 0.87
     *   "8.7"         → 0.87   (0–10 scale → divide by 10)
     *   "9"           → 0.9    (1–10 integer scale)
     */
    internal fun parseScore(raw: String): Double? {
        val trimmed = raw.trim()

        // Direct parse: "0.87"
        trimmed.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }?.let { return it }

        // Percentage: "87%"
        Regex("""(\d{1,3})%""").find(trimmed)?.groupValues?.get(1)
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }
            ?.let { return it / 100.0 }

        // First decimal or integer in output
        Regex("""\b(\d+\.?\d*)\b""").find(trimmed)?.groupValues?.get(1)
            ?.toDoubleOrNull()
            ?.let { v ->
                return when {
                    v in 0.0..1.0  -> v               // already in [0, 1]
                    v in 1.0..10.0 -> v / 10.0        // 0–10 scale → [0, 1]
                    else           -> null
                }
            }

        return null
    }
}
