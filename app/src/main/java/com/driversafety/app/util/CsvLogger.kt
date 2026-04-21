package com.driversafety.app.util

import android.content.Context
import android.util.Log
import com.driversafety.app.data.SensorReading
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ══════════════════════════════════════════════════════════════════════════
 * CsvLogger.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Writes [SensorReading] rows to a timestamped `.csv` file stored in
 * the app's internal cache directory for debugging and auditing.
 *
 * ── File location ──────────────────────────────────────────────────────
 * `<cacheDir>/sensor_logs/driving_log_<yyyyMMdd_HHmmss>.csv`
 *
 * ── Pulling the file from the device ───────────────────────────────────
 * ```bash
 * adb shell run-as com.driversafety.app \
 *     cat /data/data/com.driversafety.app/cache/sensor_logs/<filename>.csv
 * ```
 *
 * ── Thread safety ──────────────────────────────────────────────────────
 * All writes go through a [BufferedWriter] that is opened in [start]
 * and flushed/closed in [stop]. Callers should ensure they don't
 * call [log] after [stop].
 */
class CsvLogger(private val context: Context) {

    companion object {
        private const val TAG = "CsvLogger"
        private const val LOG_DIR = "sensor_logs"
    }

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    /**
     * Opens a new CSV file and writes the header row.
     * Call this when the user presses "Start".
     */
    fun start() {
        try {
            // Ensure the log directory exists
            val dir = File(context.cacheDir, LOG_DIR).also { it.mkdirs() }

            // Generate a unique filename based on the current timestamp
            val timestamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss", Locale.US
            ).format(Date())
            val file = File(dir, "driving_log_$timestamp.csv")
            currentFile = file

            // Open a buffered writer in append mode and write the CSV header
            writer = BufferedWriter(FileWriter(file, true))
            writer?.apply {
                write(SensorReading.CSV_HEADER)
                newLine()
                flush()
            }

            Log.i(TAG, "📝 CSV logging started → ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CSV logger: ${e.message}", e)
        }
    }

    /**
     * Appends a single [SensorReading] row to the open CSV.
     * Called from a coroutine that collects the SensorDataCollector's Flow.
     */
    fun log(reading: SensorReading) {
        try {
            writer?.apply {
                write(reading.toCsvRow())
                newLine()
                // Note: we don't flush on every write for performance.
                // Flushing happens on stop() or when the buffer is full.
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSV write error: ${e.message}")
        }
    }

    /**
     * Flushes and closes the CSV file.
     * Call this when the user presses "Stop".
     *
     * @return The absolute path to the completed CSV file (for debug
     *         display), or null if no file was created.
     */
    fun stop(): String? {
        return try {
            writer?.flush()
            writer?.close()
            writer = null
            val path = currentFile?.absolutePath
            Log.i(TAG, "📝 CSV logging stopped → $path")
            path
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close CSV: ${e.message}", e)
            null
        }
    }
}
