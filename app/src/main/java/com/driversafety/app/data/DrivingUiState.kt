package com.driversafety.app.data

/**
 * ══════════════════════════════════════════════════════════════════════════
 * DrivingUiState.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Represents the full UI state of the single-screen dashboard.
 * Observed by the Compose UI via StateFlow from the DrivingViewModel.
 *
 * @property status         Current recording status (IDLE / RECORDING / STOPPED).
 * @property latestRating   Most recent model output (1-5), null when idle.
 * @property averageRating  Average of ALL ratings recorded in this session.
 *                          Populated only after the user clicks "Stop".
 * @property ratingsCount   Number of inference cycles completed so far.
 * @property elapsedSeconds How many seconds have elapsed since recording
 *                          started (for a live timer on the UI).
 */
data class DrivingUiState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val latestRating: Int? = null,
    val averageRating: Float? = null,
    val ratingsCount: Int = 0,
    val elapsedSeconds: Long = 0L
)

/**
 * Simple enum for the three possible states of the app.
 */
enum class RecordingStatus {
    /** App is waiting for the user to press "Start". */
    IDLE,
    /** Sensors are active and inference is running every 5 seconds. */
    RECORDING,
    /** The session has ended and the trip summary is displayed. */
    STOPPED
}
