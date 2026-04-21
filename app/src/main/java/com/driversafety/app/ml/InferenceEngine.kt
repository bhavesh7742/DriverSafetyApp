package com.driversafety.app.ml

import android.content.Context
import android.util.Log
import com.driversafety.app.data.SensorReading
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

/**
 * ══════════════════════════════════════════════════════════════════════════
 * InferenceEngine.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Loads a **TorchScript Lite** model (`.ptl`) from the `assets/model/`
 * directory and performs on-device inference on sensor data to produce
 * a driving safety rating from **1** (rash) to **5** (professional).
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  HOW TO PROVIDE YOUR OWN MODEL                                      │
 * │                                                                      │
 * │  1. Train your PyTorch model in Python.                              │
 * │  2. Export it with TorchScript:                                      │
 * │     ┌───────────────────────────────────────────────────────────┐    │
 * │     │  import torch                                             │    │
 * │     │  from torch.utils.mobile_optimizer import (               │    │
 * │     │      optimize_for_mobile                                  │    │
 * │     │  )                                                        │    │
 * │     │                                                           │    │
 * │     │  model = YourModel()                                      │    │
 * │     │  model.load_state_dict(torch.load("weights.pth"))         │    │
 * │     │  model.eval()                                             │    │
 * │     │                                                           │    │
 * │     │  # Option A — Script (preferred):                         │    │
 * │     │  scripted = torch.jit.script(model)                       │    │
 * │     │                                                           │    │
 * │     │  # Option B — Trace (if scripting fails):                 │    │
 * │     │  # example = torch.randn(1, 250, 6)                      │    │
 * │     │  # scripted = torch.jit.trace(model, example)             │    │
 * │     │                                                           │    │
 * │     │  optimized = optimize_for_mobile(scripted)                │    │
 * │     │  optimized._save_for_lite_interpreter(                    │    │
 * │     │      "driver_model.ptl"                                   │    │
 * │     │  )                                                        │    │
 * │     └───────────────────────────────────────────────────────────┘    │
 * │  3. Copy driver_model.ptl into:                                      │
 * │     app/src/main/assets/model/driver_model.ptl                       │
 * │  4. Rebuild the app — this class loads it automatically.             │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ── Expected model I/O ─────────────────────────────────────────────────
 * | Direction | Shape            | Notes                                |
 * |-----------|------------------|----------------------------------------|
 * | Input     | [1, SeqLen, 6]   | 6 = accelX/Y/Z + gyroX/Y/Z            |
 * | Output    | [1, 5]           | 5-class logits; argmax+1 = rating 1..5 |
 *
 * If no model file is found in assets, the engine falls back to a
 * **deterministic heuristic** (based on accelerometer magnitude) so the
 * app remains functional during development/testing.
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "InferenceEngine"

        // ────────────────────────────────────────────────────────────────
        // ⬇️  CHANGE THIS to match the filename of YOUR TorchScript
        //     model placed inside  assets/model/
        // ────────────────────────────────────────────────────────────────
        private const val MODEL_FILENAME = "driver_model.ptl"
        private const val MODEL_ASSET_PATH = "model/$MODEL_FILENAME"
    }

    /** Loaded PyTorch module, null if the asset is missing. */
    private var module: Module? = null

    /**
     * True when the real PyTorch model is loaded; false when using
     * the heuristic fallback. Exposed to the UI as a badge.
     */
    var isModelLoaded: Boolean = false
        private set

    init {
        // Attempt to load the model immediately on construction
        loadModel()
    }

    // ════════════════════════════════════════════════════════════════════
    // Model loading
    // ════════════════════════════════════════════════════════════════════

    /**
     * Attempts to copy the `.ptl` file from assets to the cache dir
     * (PyTorch Mobile requires a file-system path) and load it.
     * Falls back gracefully if the file is absent.
     */
    private fun loadModel() {
        try {
            val modelFile = assetFilePath(MODEL_ASSET_PATH)
            if (modelFile != null) {
                module = LiteModuleLoader.load(modelFile)
                isModelLoaded = true
                Log.i(TAG, "✅ PyTorch model loaded from $MODEL_ASSET_PATH")
            } else {
                Log.w(TAG, "⚠️ Model file not found in assets — using heuristic fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load model: ${e.message}", e)
            module = null
            isModelLoaded = false
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Inference — public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Runs inference on the supplied sensor readings and returns an
     * **integer rating from 1 to 5**.
     *
     * If the real model is loaded → real PyTorch inference.
     * Otherwise → deterministic heuristic based on accelerometer magnitude.
     *
     * @param readings  The most recent 5 seconds of [SensorReading]s.
     * @return          1 = rash / dangerous driving,
     *                  5 = smooth / professional driving.
     */
    fun predict(readings: List<SensorReading>): Int {
        // Edge case: no data available → return neutral rating
        if (readings.isEmpty()) return 3

        // If the real model is loaded, run real inference
        module?.let { mod ->
            return runModelInference(mod, readings)
        }

        // ── Fallback: heuristic based on accelerometer magnitude ────────
        return heuristicRating(readings)
    }

    // ════════════════════════════════════════════════════════════════════
    // Real PyTorch inference
    // ════════════════════════════════════════════════════════════════════

    /**
     * Converts the list of readings to a [Tensor] of shape
     * `[1, SeqLen, 6]`, runs the model's forward pass, and decodes
     * the output logits into a 1–5 rating.
     *
     * The 6 features per timestep are ordered:
     *   [accelX, accelY, accelZ, gyroX, gyroY, gyroZ]
     */
    private fun runModelInference(mod: Module, readings: List<SensorReading>): Int {
        val seqLen = readings.size

        // Build a flat float array: seqLen * 6 elements
        val floatData = FloatArray(seqLen * 6)
        readings.forEachIndexed { i, r ->
            val offset = i * 6
            floatData[offset + 0] = r.accelX
            floatData[offset + 1] = r.accelY
            floatData[offset + 2] = r.accelZ
            floatData[offset + 3] = r.gyroX
            floatData[offset + 4] = r.gyroY
            floatData[offset + 5] = r.gyroZ
        }

        // Shape: [1, seqLen, 6] — batch dimension required by most models
        val inputTensor = Tensor.fromBlob(
            floatData,
            longArrayOf(1L, seqLen.toLong(), 6L)
        )

        // ── Forward pass ────────────────────────────────────────────────
        val outputTensor = mod.forward(IValue.from(inputTensor)).toTensor()
        val scores = outputTensor.dataAsFloatArray   // expected: 5 floats

        // Argmax over the 5-class logits → index 0..4 → rating 1..5
        val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        return (maxIndex + 1).coerceIn(1, 5)
    }

    // ════════════════════════════════════════════════════════════════════
    // Heuristic fallback (no real model)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Simple heuristic that maps the **average accelerometer magnitude**
     * to a 1-5 rating. Useful for demo / development without a trained model.
     *
     * Rationale: A phone at rest shows ~9.81 m/s² (gravity). Smooth
     * driving adds little extra. Aggressive acceleration, braking, and
     * turning push the magnitude higher.
     *
     * | Avg Magnitude (m/s²) | Rating |
     * |----------------------|--------|
     * | < 10.5               | 5      |  (smooth, mostly gravity)
     * | 10.5 – 12.0          | 4      |
     * | 12.0 – 15.0          | 3      |
     * | 15.0 – 20.0          | 2      |
     * | > 20.0               | 1      |  (aggressive forces)
     */
    private fun heuristicRating(readings: List<SensorReading>): Int {
        val avgMag = readings.map { r ->
            Math.sqrt(
                (r.accelX * r.accelX +
                 r.accelY * r.accelY +
                 r.accelZ * r.accelZ).toDouble()
            )
        }.average()

        return when {
            avgMag < 10.5 -> 5
            avgMag < 12.0 -> 4
            avgMag < 15.0 -> 3
            avgMag < 20.0 -> 2
            else          -> 1
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Utility: copy asset → cache so PyTorch can load from a file path
    // ════════════════════════════════════════════════════════════════════

    /**
     * Copies an asset file to the app's cache directory and returns
     * its absolute path. Returns null if the asset doesn't exist.
     */
    private fun assetFilePath(assetName: String): String? {
        return try {
            val file = File(context.cacheDir, assetName.replace("/", "_"))
            // Skip copy if the file already exists and has content
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetName not found: ${e.message}")
            null
        }
    }
}
