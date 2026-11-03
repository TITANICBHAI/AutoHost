package com.ariaagent.mobile.core.rl

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PolicyNetwork — The RL agent's fast action selection brain.
 *
 * This is NOT the LLM. The LLM handles language reasoning (navigation, complex tasks).
 * The policy network handles fast, repeated action selection (games, repetitive UI patterns).
 *
 * Architecture: small MLP, 3 layers, ~5MB on disk
 *   Input:  128-dim screen embedding + 128-dim goal embedding = 256 floats
 *   Hidden: 256 → 128 neurons (ReLU activations)
 *   Output: 7 action probabilities (softmax)
 *
 * Training algorithm: REINFORCE (Williams, 1992)
 *   - Policy gradient method — no value function needed
 *   - Update: θ ← θ + α * G_t * ∇_θ log π_θ(a_t | s_t)
 *   - Optimizer: Adam (adaptive moment estimation) for stable convergence
 *
 * Math backend: NEON SIMD via aria_math.cpp (JNI)
 *   nativeMatVecRelu() — matrix-vector multiply + ReLU for hidden layers
 *   nativeSoftmax()    — numerically stable softmax for output
 *   Falls back to Kotlin scalar math if .so not loaded.
 *
 * Action space (7):
 *   0 = tap          4 = swipe-left
 *   1 = swipe-up     5 = type
 *   2 = swipe-down   6 = back
 *   3 = swipe-right
 *
 * Saved weights: rl/policy_latest.bin (little-endian float32 binary)
 * Adam state:    rl/policy_adam.bin
 *
 * Training runs ONLY during idle + charging. See LearningScheduler.
 *
 * Phase: 5 (RL/IRL Processing)
 */
object PolicyNetwork {

    private const val TAG = "PolicyNetwork"

    private const val INPUT_DIM  = 256
    private const val HIDDEN1    = 256
    private const val HIDDEN2    = 128
    private const val OUTPUT_DIM = 7

    private const val LEARNING_RATE = 1e-4f
    private const val DISCOUNT_GAMMA = 0.99f
    private const val BASELINE_DECAY = 0.95f   // exponential moving average for reward baseline

    val actionNames = arrayOf("tap", "swipe_up", "swipe_down", "swipe_right", "swipe_left", "type", "back")

    // ─── Network weights ─────────────────────────────────────────────────────
    private var weights1: FloatArray? = null    // shape: (HIDDEN1 × INPUT_DIM) row-major
    private var weights2: FloatArray? = null    // shape: (HIDDEN2 × HIDDEN1) row-major
    private var outputW:  FloatArray? = null    // shape: (OUTPUT_DIM × HIDDEN2) row-major

    // ─── Adam optimizer state ─────────────────────────────────────────────────
    // First moment (mean of gradients)
    private var m1: FloatArray? = null;  private var m2: FloatArray? = null;  private var mOut: FloatArray? = null
    // Second moment (uncentered variance of gradients)
    private var v1: FloatArray? = null;  private var v2: FloatArray? = null;  private var vOut: FloatArray? = null
    private var adamStep = 0

    // ─── Reward baseline ──────────────────────────────────────────────────────
    private var rewardBaseline = 0f

    private var isInitialized = false
    private var neonAvailable = false

    // ─── Exposed stats (read by TrainScreen via AgentViewModel) ──────────────
    var lastPolicyLoss: Double = 0.0
        private set

    val adamStepCount: Int get() = adamStep

    fun isReady(): Boolean = isInitialized

    // ─── JNI (aria_math.cpp) ─────────────────────────────────────────────────

    private external fun nativeMatVecRelu(W: FloatArray, x: FloatArray, rows: Int, cols: Int): FloatArray
    private external fun nativeSoftmax(logits: FloatArray): FloatArray

    init {
        try {
            System.loadLibrary("llama-jni")
            neonAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "NEON not available — using Kotlin scalar math")
        }
    }

    // ─── Load / init ─────────────────────────────────────────────────────────

    fun load(context: Context) {
        val rlDir = File(context.filesDir, "rl").also { it.mkdirs() }
            .let { i -> if (i.canWrite()) i else (context.getExternalFilesDir("rl") ?: i).also { it.mkdirs() } }
        val weightsFile = File(rlDir, "policy_latest.bin")
        if (weightsFile.exists() && weightsFile.length() > 100L) {
            loadFromBinary(weightsFile)
        } else {
            initRandom()
        }
        val adamFile = File(rlDir, "policy_adam.bin")
        if (adamFile.exists() && adamFile.length() > 100L) {
            loadAdamState(adamFile)
        } else {
            initAdamState()
        }
        isInitialized = true
        Log.i(TAG, "PolicyNetwork loaded (neon=$neonAvailable, fresh=${!weightsFile.exists()})")
    }

    // ─── Forward pass ─────────────────────────────────────────────────────────

    /**
     * Forward pass: input embedding → action probabilities.
     * @return (actionIndex, confidence) pair
     */
    fun selectAction(screenEmbedding: FloatArray, goalEmbedding: FloatArray): Pair<Int, Float> {
        if (!isInitialized) return Pair(0, 0f)
        val input = FloatArray(INPUT_DIM)
        val sLen = minOf(screenEmbedding.size, 128)
        val gLen = minOf(goalEmbedding.size, 128)
        System.arraycopy(screenEmbedding, 0, input, 0, sLen)
        System.arraycopy(goalEmbedding, 0, input, 128, gLen)
        val (probs, _, _) = forwardWithActivations(input)
        val idx = probs.indices.maxByOrNull { probs[it] } ?: 0
        return Pair(idx, probs[idx])
    }

    /**
     * Forward pass returning intermediate activations (needed for backprop).
     * @return Triple(probs, h1, h2)
     */
    private fun forwardWithActivations(input: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val w1 = weights1 ?: return Triple(uniformProbs(), FloatArray(HIDDEN1), FloatArray(HIDDEN2))
        val w2 = weights2 ?: return Triple(uniformProbs(), FloatArray(HIDDEN1), FloatArray(HIDDEN2))
        val wo = outputW  ?: return Triple(uniformProbs(), FloatArray(HIDDEN1), FloatArray(HIDDEN2))

        val h1 = if (neonAvailable) nativeMatVecRelu(w1, input, HIDDEN1, INPUT_DIM)
                 else matVecReluKotlin(w1, input, HIDDEN1, INPUT_DIM)

        val h2 = if (neonAvailable) nativeMatVecRelu(w2, h1, HIDDEN2, HIDDEN1)
                 else matVecReluKotlin(w2, h1, HIDDEN2, HIDDEN1)

        val logits = matVecKotlin(wo, h2, OUTPUT_DIM, HIDDEN2)  // no ReLU before softmax

        val probs = if (neonAvailable) nativeSoftmax(logits) else softmaxKotlin(logits)

        return Triple(probs, h1, h2)
    }

    private fun uniformProbs() = FloatArray(OUTPUT_DIM) { 1f / OUTPUT_DIM }

    // ─── REINFORCE training ───────────────────────────────────────────────────

    /**
     * REINFORCE policy gradient update.
     *
     * Algorithm (Williams, 1992):
     *   For each step t in episode:
     *     G_t = sum_{k=t}^{T} γ^{k-t} * r_k   (discounted return)
     *     Advantage = G_t - baseline            (reduce variance)
     *     loss_t = -log(π(a_t|s_t)) * advantage
     *     ∇_θ J ≈ mean(∇_θ loss_t) over episode
     *
     * Optimizer: Adam for stable convergence on sparse reward signals.
     *
     * @param states  List of screen+goal concatenated input vectors (each 256-dim)
     * @param actions List of action indices taken at each step
     * @param rewards List of rewards received at each step
     * @return mean episode return (for logging)
     */
    fun reinforce(
        states:  List<FloatArray>,
        actions: List<Int>,
        rewards: List<Double>
    ): Double {
        if (!isInitialized || states.isEmpty()) return 0.0
        if (states.size != actions.size || actions.size != rewards.size) return 0.0

        val T = states.size

        // ── Step 1: Compute discounted returns ──────────────────────────────
        val returns = DoubleArray(T)
        var G = 0.0
        for (t in T - 1 downTo 0) {
            G = rewards[t] + DISCOUNT_GAMMA * G
            returns[t] = G
        }

        // ── Step 2: Normalize returns (reduce variance) ─────────────────────
        val meanR = returns.average()
        val stdR  = kotlin.math.sqrt(returns.map { (it - meanR) * (it - meanR) }.average()).coerceAtLeast(1e-6)
        val normalizedReturns = DoubleArray(T) { (returns[it] - meanR) / stdR }

        // ── Step 3: Accumulate gradients over episode ───────────────────────
        val dW1 = FloatArray(HIDDEN1 * INPUT_DIM)
        val dW2 = FloatArray(HIDDEN2 * HIDDEN1)
        val dWo = FloatArray(OUTPUT_DIM * HIDDEN2)

        for (t in 0 until T) {
            val input  = states[t].let { s ->
                if (s.size == INPUT_DIM) s else FloatArray(INPUT_DIM).also {
                    System.arraycopy(s, 0, it, 0, minOf(s.size, INPUT_DIM))
                }
            }
            val action = actions[t].coerceIn(0, OUTPUT_DIM - 1)
            val Gt     = normalizedReturns[t].toFloat()

            val (probs, h1, h2) = forwardWithActivations(input)

            // ── Policy gradient at output layer ─────────────────────────────
            // delta_out = G_t * (probs - one_hot(a))
            // This is the softmax + cross-entropy gradient scaled by the return
            val deltaOut = FloatArray(OUTPUT_DIM) { i ->
                Gt * (probs[i] - if (i == action) 1f else 0f)
            }

            // ── dW_out += delta_out ⊗ h2 (outer product) ───────────────────
            for (i in 0 until OUTPUT_DIM) {
                for (j in 0 until HIDDEN2) {
                    dWo[i * HIDDEN2 + j] += deltaOut[i] * h2[j]
                }
            }

            // ── Backprop through layer 2 ────────────────────────────────────
            // delta_h2 = (W_out^T · delta_out) ⊙ relu_grad(h2)
            val wo = outputW!!
            val deltaH2 = FloatArray(HIDDEN2) { j ->
                var d = 0f
                for (i in 0 until OUTPUT_DIM) d += wo[i * HIDDEN2 + j] * deltaOut[i]
                if (h2[j] > 0f) d else 0f  // ReLU gradient: 1 if h2>0, else 0
            }

            // ── dW2 += delta_h2 ⊗ h1 ───────────────────────────────────────
            for (i in 0 until HIDDEN2) {
                for (j in 0 until HIDDEN1) {
                    dW2[i * HIDDEN1 + j] += deltaH2[i] * h1[j]
                }
            }

            // ── Backprop through layer 1 ────────────────────────────────────
            val w2 = weights2!!
            val deltaH1 = FloatArray(HIDDEN1) { j ->
                var d = 0f
                for (i in 0 until HIDDEN2) d += w2[i * HIDDEN1 + j] * deltaH2[i]
                if (h1[j] > 0f) d else 0f  // ReLU gradient
            }

            // ── dW1 += delta_h1 ⊗ input ────────────────────────────────────
            for (i in 0 until HIDDEN1) {
                for (j in 0 until INPUT_DIM) {
                    dW1[i * INPUT_DIM + j] += deltaH1[i] * input[j]
                }
            }
        }

        // ── Step 4: Average gradients over episode ──────────────────────────
        val scale = 1f / T
        for (i in dW1.indices) dW1[i] *= scale
        for (i in dW2.indices) dW2[i] *= scale
        for (i in dWo.indices) dWo[i] *= scale

        // ── Step 5: Adam optimizer update ───────────────────────────────────
        adamStep++
        adamUpdate(weights1!!, dW1, m1!!, v1!!, adamStep)
        adamUpdate(weights2!!, dW2, m2!!, v2!!, adamStep)
        adamUpdate(outputW!!,  dWo, mOut!!, vOut!!, adamStep)

        val episodeReturn = returns[0]
        // policy loss = -mean(log π(a|s) * G_t) over episode, for logging
        lastPolicyLoss = -returns.average()
        Log.d(TAG, "REINFORCE step $adamStep — T=$T return=${episodeReturn.toFloat()} loss=${lastPolicyLoss.toFloat()} meanReward=${rewards.average().toFloat()}")
        return episodeReturn
    }

    // ─── Adam optimizer ───────────────────────────────────────────────────────
    // Adam: Adaptive Moment Estimation (Kingma & Ba, 2015)
    //   m = β1 * m + (1-β1) * g         (first moment — momentum)
    //   v = β2 * v + (1-β2) * g²        (second moment — variance)
    //   m̂ = m / (1-β1^t)                (bias-corrected)
    //   v̂ = v / (1-β2^t)                (bias-corrected)
    //   W -= lr * m̂ / (√v̂ + ε)

    private const val BETA1 = 0.9f
    private const val BETA2 = 0.999f
    private const val ADAM_EPS = 1e-8f

    private fun adamUpdate(W: FloatArray, g: FloatArray, m: FloatArray, v: FloatArray, t: Int) {
        val bc1 = 1f - BETA1.pow(t)  // bias correction for first moment
        val bc2 = 1f - BETA2.pow(t)  // bias correction for second moment
        for (i in W.indices) {
            m[i] = BETA1 * m[i] + (1f - BETA1) * g[i]
            v[i] = BETA2 * v[i] + (1f - BETA2) * g[i] * g[i]
            val mHat = m[i] / bc1
            val vHat = v[i] / bc2
            W[i] -= LEARNING_RATE * mHat / (kotlin.math.sqrt(vHat.toDouble()).toFloat() + ADAM_EPS)
        }
    }

    private fun Float.pow(n: Int): Float {
        var result = 1f
        repeat(n.coerceAtMost(100)) { result *= this }
        return result
    }

    // ─── Persistence — real binary serialization ─────────────────────────────

    fun saveToFile(context: Context) {
        if (!isInitialized) return
        try {
            val dir = File(context.filesDir, "rl").also { it.mkdirs() }
                .let { i -> if (i.canWrite()) i else (context.getExternalFilesDir("rl") ?: i).also { it.mkdirs() } }

            // Save weights as little-endian float32 binary
            DataOutputStream(FileOutputStream(File(dir, "policy_latest.bin"))).use { out ->
                out.writeInt(HIDDEN1 * INPUT_DIM)
                weights1!!.forEach { out.writeFloat(it) }
                out.writeInt(HIDDEN2 * HIDDEN1)
                weights2!!.forEach { out.writeFloat(it) }
                out.writeInt(OUTPUT_DIM * HIDDEN2)
                outputW!!.forEach { out.writeFloat(it) }
                out.writeFloat(rewardBaseline)
                out.writeInt(adamStep)
            }

            // Save Adam state separately (large — only save periodically)
            DataOutputStream(FileOutputStream(File(dir, "policy_adam.bin"))).use { out ->
                m1!!.forEach { out.writeFloat(it) }
                v1!!.forEach { out.writeFloat(it) }
                m2!!.forEach { out.writeFloat(it) }
                v2!!.forEach { out.writeFloat(it) }
                mOut!!.forEach { out.writeFloat(it) }
                vOut!!.forEach { out.writeFloat(it) }
            }

            Log.i(TAG, "PolicyNetwork saved — step=$adamStep baseline=$rewardBaseline")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }

    private fun loadFromBinary(file: File) {
        try {
            DataInputStream(FileInputStream(file)).use { din ->
                val s1 = din.readInt()
                weights1 = FloatArray(s1) { din.readFloat() }
                val s2 = din.readInt()
                weights2 = FloatArray(s2) { din.readFloat() }
                val sO = din.readInt()
                outputW  = FloatArray(sO) { din.readFloat() }
                rewardBaseline = din.readFloat()
                adamStep = din.readInt()
            }
            Log.i(TAG, "PolicyNetwork loaded from file — step=$adamStep")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load weights from file — reinitializing: ${e.message}")
            initRandom()
        }
    }

    private fun loadAdamState(file: File) {
        try {
            DataInputStream(FileInputStream(file)).use { din ->
                m1   = FloatArray(HIDDEN1 * INPUT_DIM) { din.readFloat() }
                v1   = FloatArray(HIDDEN1 * INPUT_DIM) { din.readFloat() }
                m2   = FloatArray(HIDDEN2 * HIDDEN1) { din.readFloat() }
                v2   = FloatArray(HIDDEN2 * HIDDEN1) { din.readFloat() }
                mOut = FloatArray(OUTPUT_DIM * HIDDEN2) { din.readFloat() }
                vOut = FloatArray(OUTPUT_DIM * HIDDEN2) { din.readFloat() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Adam state not loadable — reinitializing: ${e.message}")
            initAdamState()
        }
    }

    // ─── Init ─────────────────────────────────────────────────────────────────

    private fun initRandom() {
        val rng = java.util.Random(42L)
        // Xavier/Glorot initialization: σ = sqrt(2 / (fan_in + fan_out))
        val scale1 = kotlin.math.sqrt(2.0 / (INPUT_DIM + HIDDEN1)).toFloat()
        val scale2 = kotlin.math.sqrt(2.0 / (HIDDEN1 + HIDDEN2)).toFloat()
        val scaleO = kotlin.math.sqrt(2.0 / (HIDDEN2 + OUTPUT_DIM)).toFloat()

        weights1 = FloatArray(HIDDEN1 * INPUT_DIM)  { (rng.nextGaussian() * scale1).toFloat() }
        weights2 = FloatArray(HIDDEN2 * HIDDEN1)    { (rng.nextGaussian() * scale2).toFloat() }
        outputW  = FloatArray(OUTPUT_DIM * HIDDEN2) { (rng.nextGaussian() * scaleO).toFloat() }
    }

    private fun initAdamState() {
        m1   = FloatArray(HIDDEN1 * INPUT_DIM)
        v1   = FloatArray(HIDDEN1 * INPUT_DIM)
        m2   = FloatArray(HIDDEN2 * HIDDEN1)
        v2   = FloatArray(HIDDEN2 * HIDDEN1)
        mOut = FloatArray(OUTPUT_DIM * HIDDEN2)
        vOut = FloatArray(OUTPUT_DIM * HIDDEN2)
        adamStep = 0
    }

    // ─── Kotlin scalar fallbacks ──────────────────────────────────────────────

    private fun matVecReluKotlin(W: FloatArray, x: FloatArray, rows: Int, cols: Int): FloatArray =
        FloatArray(rows) { i ->
            var s = 0f
            for (j in 0 until cols) s += W[i * cols + j] * x[j]
            if (s > 0f) s else 0f
        }

    private fun matVecKotlin(W: FloatArray, x: FloatArray, rows: Int, cols: Int): FloatArray =
        FloatArray(rows) { i ->
            var s = 0f
            for (j in 0 until cols) s += W[i * cols + j] * x[j]
            s
        }

    private fun softmaxKotlin(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exp = FloatArray(logits.size) { kotlin.math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exp.sum().coerceAtLeast(1e-10f)
        return FloatArray(exp.size) { exp[it] / sum }
    }
}
