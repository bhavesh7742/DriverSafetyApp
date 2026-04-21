package com.driversafety.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driversafety.app.data.DrivingUiState
import com.driversafety.app.data.RecordingStatus
import com.driversafety.app.ml.InferenceEngine
import com.driversafety.app.sensor.SensorDataCollector
import com.driversafety.app.util.CsvLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════════════════════════════════════
 * DrivingViewModel.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Central orchestrator for the driving safety dashboard.
 *
 * ── Responsibilities ───────────────────────────────────────────────────
 * • Start / stop the [SensorDataCollector].
 * • Start / stop the [CsvLogger].
 * • Run a **5-second inference ticker** using [InferenceEngine].
 * • Accumulate ratings and compute the trip average on stop.
 * • Expose a [DrivingUiState] via [uiState] for Compose to observe.
 *
 * ── Why AndroidViewModel? ──────────────────────────────────────────────
 * We need [Application] context to initialise the sensor manager, CSV
 * logger, and PyTorch model — but we must NOT hold an Activity reference.
 * AndroidViewModel gives us an Application-scoped context that's safe.
 *
 * ── Lifecycle ──────────────────────────────────────────────────────────
 * The ViewModel **survives configuration changes** (screen rotation).
 * This means an ongoing recording session is not interrupted when the
 * user rotates the device. When the Activity is truly destroyed (user
 * leaves), [onCleared] fires and we stop all sensors.
 *
 * ── Coroutine structure ────────────────────────────────────────────────
 * Three concurrent coroutines run during recording:
 *   1. **inferenceJob** — ticks every 5 seconds, runs model predict()
 *   2. **timerJob**     — ticks every 1 second, updates elapsed counter
 *   3. **csvJob**       — collects the sensor SharedFlow, writes CSV rows
 */
class DrivingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DrivingViewModel"
        /** The inference engine is triggered every 5 000 ms. */
        private const val INFERENCE_INTERVAL_MS = 5_000L
        /** The elapsed-time counter ticks every 1 000 ms. */
        private const val TIMER_TICK_MS = 1_000L
    }

    // ── Components (initialised once, survive rotations) ────────────────
    val sensorCollector = SensorDataCollector(application)
    private val inferenceEngine = InferenceEngine(application)
    private val csvLogger = CsvLogger(application)

    // ── Observable UI state exposed to Compose ─────────────────────────
    private val _uiState = MutableStateFlow(DrivingUiState())
    val uiState: StateFlow<DrivingUiState> = _uiState.asStateFlow()

    /** Whether the loaded model is real or the heuristic fallback. */
    val isRealModelLoaded: Boolean get() = inferenceEngine.isModelLoaded

    // ── Internal bookkeeping ────────────────────────────────────────────
    private val ratings = mutableListOf<Int>()   // all ratings this session
    private var inferenceJob: Job? = null
    private var timerJob: Job? = null
    private var csvJob: Job? = null

    // ════════════════════════════════════════════════════════════════════
    // Public actions — called by the UI
    // ════════════════════════════════════════════════════════════════════

    /**
     * Begin a new recording session.
     *
     * Steps:
     * 1. Reset state (clear old ratings)
     * 2. Register sensor listeners
     * 3. Open a new CSV file
     * 4. Launch the 5-second inference ticker
     * 5. Launch the 1-second elapsed timer
     */
    fun startRecording() {
        // Guard: don't double-start
        if (_uiState.value.status == RecordingStatus.RECORDING) return

        // Reset state for a fresh session
        ratings.clear()
        _uiState.value = DrivingUiState(status = RecordingStatus.RECORDING)

        // 1) Start sensors — Accel + Gyro listeners
        sensorCollector.start()

        // 2) Start CSV logging — subscribe to the sensor readings Flow
        csvLogger.start()
        csvJob = viewModelScope.launch {
            sensorCollector.readings.collect { reading ->
                csvLogger.log(reading)
            }
        }

        // 3) Inference ticker — runs every 5 seconds
        inferenceJob = viewModelScope.launch {
            // Give sensors 1 second to warm up before the first inference
            delay(1_000L)
            while (isActive) {
                delay(INFERENCE_INTERVAL_MS)
                runInference()
            }
        }

        // 4) Elapsed timer — ticks every second for the UI counter
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(TIMER_TICK_MS)
                _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }

        Log.i(TAG, "▶️ Recording started")
    }

    /**
     * Stop the current recording session.
     *
     * Steps:
     * 1. Cancel all coroutines (inference, timer, CSV)
     * 2. Unregister sensor listeners
     * 3. Close the CSV file
     * 4. Compute the mathematical average of all collected ratings
     * 5. Transition UI to STOPPED state
     */
    fun stopRecording() {
        // Guard: only stop if we're actually recording
        if (_uiState.value.status != RecordingStatus.RECORDING) return

        // Cancel all background coroutines
        inferenceJob?.cancel()
        timerJob?.cancel()
        csvJob?.cancel()

        // Stop sensors and CSV logger
        sensorCollector.stop()
        val csvPath = csvLogger.stop()
        Log.i(TAG, "⏹ Recording stopped. CSV → $csvPath")

        // Compute the average of all ratings collected during this session
        val avg = if (ratings.isNotEmpty()) {
            ratings.average().toFloat()
        } else {
            null   // no ratings if session was too short
        }

        // Update UI to show the trip summary
        _uiState.update {
            it.copy(
                status = RecordingStatus.STOPPED,
                averageRating = avg
            )
        }

        Log.i(TAG, "📊 Trip average = $avg  (${ratings.size} samples)")
    }

    /**
     * Reset the UI back to IDLE state so the user can start a new trip.
     */
    fun reset() {
        ratings.clear()
        _uiState.value = DrivingUiState()
    }

    // ════════════════════════════════════════════════════════════════════
    // Inference — 5-second loop body
    // ════════════════════════════════════════════════════════════════════

    /**
     * Grabs the last 5 seconds of sensor data from the rolling buffer,
     * runs the PyTorch model (or heuristic), and updates the UI with
     * the resulting 1-5 rating.
     */
    private fun runInference() {
        // Get the most recent 5 seconds of sensor data
        val recentData = sensorCollector.getRecentReadings(INFERENCE_INTERVAL_MS)
        if (recentData.isEmpty()) {
            Log.w(TAG, "No sensor data available for inference")
            return
        }

        // Run the model (or heuristic fallback)
        val rating = inferenceEngine.predict(recentData)

        // Store the rating for the session average
        ratings.add(rating)

        // Push the new rating to the UI
        _uiState.update {
            it.copy(
                latestRating = rating,
                ratingsCount = ratings.size
            )
        }

        Log.d(TAG, "🔮 Inference #${ratings.size}: rating = $rating  " +
                "(from ${recentData.size} readings)")
    }

    // ════════════════════════════════════════════════════════════════════
    // Lifecycle — safety net
    // ════════════════════════════════════════════════════════════════════

    /**
     * Called when the ViewModel is destroyed (Activity finishes).
     * Ensures all sensors and files are properly released.
     */
    override fun onCleared() {
        super.onCleared()
        sensorCollector.stop()
        csvLogger.stop()
        Log.i(TAG, "ViewModel cleared — all resources released")
    }
}
