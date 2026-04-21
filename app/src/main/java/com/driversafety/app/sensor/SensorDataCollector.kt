package com.driversafety.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.driversafety.app.data.SensorReading
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * ══════════════════════════════════════════════════════════════════════════
 * SensorDataCollector.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Registers listeners for the device **Accelerometer** and **Gyroscope**,
 * merges them into [SensorReading] objects, and maintains a **rolling
 * in-memory buffer** of the most recent ~12 seconds of data.
 *
 * ── How the merge works ────────────────────────────────────────────────
 * The two sensors fire at slightly different rates (~50 Hz each at
 * SENSOR_DELAY_GAME). We keep the *latest* values from each sensor in
 * local variables. On **every** sensor event we merge the latest accel +
 * gyro values into a [SensorReading] and push it to the buffer. This
 * means the buffer may contain ~100 readings/second, but that's fine —
 * we just use all readings that fall within the 5-second inference window.
 *
 * ── Thread safety ──────────────────────────────────────────────────────
 * SensorManager delivers events on a background thread. All buffer access
 * is protected by `synchronized(bufferLock)`.
 *
 * ── Usage ──────────────────────────────────────────────────────────────
 * ```kotlin
 * val collector = SensorDataCollector(context)
 * collector.start()                                 // begin listening
 * val last5s = collector.getRecentReadings(5_000)   // for inference
 * collector.stop()                                  // unregister
 * ```
 */
class SensorDataCollector(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "SensorDataCollector"
        /**
         * How much history we keep in the rolling buffer (ms).
         * 12 seconds gives us headroom beyond the 5-second inference window.
         */
        private const val BUFFER_DURATION_MS = 12_000L
    }

    // ── Android sensor handles ──────────────────────────────────────────
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // ── Latest raw values from each sensor ──────────────────────────────
    // Updated atomically on the sensor thread; merged into SensorReading.
    private var latestAccel = floatArrayOf(0f, 0f, 0f)
    private var latestGyro  = floatArrayOf(0f, 0f, 0f)

    // ── Rolling in-memory buffer (thread-safe via synchronized) ─────────
    private val buffer = mutableListOf<SensorReading>()
    private val bufferLock = Any()

    // ── Flow that emits every new reading (for CSV logging) ─────────────
    // The CsvLogger subscribes to this Flow to persist every reading.
    private val _readings = MutableSharedFlow<SensorReading>(extraBufferCapacity = 256)
    /** Collectors can subscribe to this flow to receive each new reading. */
    val readings: SharedFlow<SensorReading> = _readings

    // ════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Start listening to both sensors at [SensorManager.SENSOR_DELAY_GAME]
     * (~20 ms cadence ≈ 50 Hz).
     *
     * If a sensor is missing on the device, a warning is logged but the
     * app keeps running — zero-filled values will be used for the missing
     * channels.
     */
    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "✅ Accelerometer listener registered")
        } ?: Log.w(TAG, "⚠️ Accelerometer not available on this device")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "✅ Gyroscope listener registered")
        } ?: Log.w(TAG, "⚠️ Gyroscope not available on this device")
    }

    /**
     * Unregister all sensor listeners and clear the buffer.
     * Safe to call even if [start] was never called.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        synchronized(bufferLock) { buffer.clear() }
        Log.d(TAG, "Sensor listeners unregistered, buffer cleared")
    }

    /**
     * Returns a **snapshot** (copy) of the readings collected over the
     * most recent [durationMs] milliseconds. Called by the inference
     * engine every 5 seconds.
     *
     * @param durationMs How far back to look (default: 5 000 ms).
     * @return A list of [SensorReading]s within the window. May be empty
     *         if no data has been collected yet.
     */
    fun getRecentReadings(durationMs: Long = 5_000): List<SensorReading> {
        val cutoff = System.currentTimeMillis() - durationMs
        synchronized(bufferLock) {
            return buffer.filter { it.timestamp >= cutoff }.toList()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // SensorEventListener callbacks
    // ════════════════════════════════════════════════════════════════════

    /**
     * Called by SensorManager on a background thread whenever either
     * accelerometer or gyroscope has a new value.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // 1) Update the latest values for the sensor that just fired
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = event.values.copyOf()   // defensive copy
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = event.values.copyOf()
            }
        }

        // 2) Merge latest accel + gyro into a single SensorReading
        val reading = SensorReading(
            timestamp = System.currentTimeMillis(),
            accelX = latestAccel[0],
            accelY = latestAccel[1],
            accelZ = latestAccel[2],
            gyroX  = latestGyro[0],
            gyroY  = latestGyro[1],
            gyroZ  = latestGyro[2]
        )

        // 3) Add to rolling buffer and evict stale entries
        synchronized(bufferLock) {
            buffer.add(reading)
            val cutoff = System.currentTimeMillis() - BUFFER_DURATION_MS
            buffer.removeAll { it.timestamp < cutoff }
        }

        // 4) Emit to Flow subscribers (CsvLogger will pick this up)
        _readings.tryEmit(reading)
    }

    /**
     * Called when sensor accuracy changes. Not relevant for this use case.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op — accuracy changes are not relevant for driving analysis.
    }
}
