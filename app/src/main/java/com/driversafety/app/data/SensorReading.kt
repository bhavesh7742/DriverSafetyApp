package com.driversafety.app.data

/**
 * ══════════════════════════════════════════════════════════════════════════
 * SensorReading.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * A single, synchronised snapshot of both the Accelerometer and the
 * Gyroscope at a given point in time.
 *
 * Why a single data class for both sensors?
 * ------------------------------------------
 * The two sensors fire independently. Rather than storing them in separate
 * lists, we merge the *latest* value from each sensor into one reading on
 * every sensor event. This guarantees every row has all 6 channels, which
 * is exactly what the PyTorch model expects as input features.
 *
 * @property timestamp  System.currentTimeMillis() when the reading was captured.
 * @property accelX     Accelerometer X-axis (m/s²).
 * @property accelY     Accelerometer Y-axis (m/s²).
 * @property accelZ     Accelerometer Z-axis (m/s²).
 * @property gyroX      Gyroscope X-axis (rad/s).
 * @property gyroY      Gyroscope Y-axis (rad/s).
 * @property gyroZ      Gyroscope Z-axis (rad/s).
 */
data class SensorReading(
    val timestamp: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float
) {
    /**
     * Returns a CSV-formatted row for logging:
     * timestamp, aX, aY, aZ, gX, gY, gZ
     */
    fun toCsvRow(): String =
        "$timestamp,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ"

    companion object {
        /** CSV header matching [toCsvRow] output */
        const val CSV_HEADER = "timestamp,accelX,accelY,accelZ,gyroX,gyroY,gyroZ"
    }
}
