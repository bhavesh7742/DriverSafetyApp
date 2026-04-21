package com.driversafety.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.driversafety.app.MainActivity

/**
 * ══════════════════════════════════════════════════════════════════════════
 * DrivingForegroundService.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * A minimal foreground service that keeps the process alive when the
 * screen turns off during a drive.
 *
 * ── What it does ───────────────────────────────────────────────────────
 * It posts a persistent notification so Android won't kill the process.
 * It does NOT own any sensor or inference logic — those live in the
 * DrivingViewModel which survives as long as the Activity (and therefore
 * the process) is alive.
 *
 * ── Why "specialUse" foreground service type? ──────────────────────────
 * Starting with Android 14 (API 34), foreground services must declare a
 * type. Since we're monitoring driving via sensors (not location), we use
 * "specialUse". In a production app you'd also add the
 * <property> tag in the manifest explaining the use case.
 *
 * ── Starting / stopping ───────────────────────────────────────────────
 * ```kotlin
 * DrivingForegroundService.start(context)   // from Activity onStart
 * DrivingForegroundService.stop(context)    // from Activity onStop
 * ```
 */
class DrivingForegroundService : Service() {

    companion object {
        private const val TAG = "DrivingFGService"
        private const val CHANNEL_ID = "driver_safety_channel"
        private const val NOTIFICATION_ID = 1001

        /**
         * Convenience method: start the foreground service from any Context.
         * Uses startForegroundService() on O+ for proper priority handling.
         */
        fun start(context: Context) {
            val intent = Intent(context, DrivingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience method: stop the foreground service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, DrivingForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Create the notification channel (required on O+)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Immediately promote to foreground with a persistent notification
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "▶️ Foreground service started")
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "⏹ Foreground service destroyed")
    }

    // ════════════════════════════════════════════════════════════════════
    // Notification plumbing
    // ════════════════════════════════════════════════════════════════════

    /**
     * Creates the notification channel (silent, low importance).
     * Required on Android 8.0 (Oreo) and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Driver Safety Recording",
                NotificationManager.IMPORTANCE_LOW   // silent, persistent
            ).apply {
                description = "Shows while driving data is being recorded"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification shown during recording.
     * Tapping it brings the user back to the main dashboard.
     */
    private fun buildNotification(): Notification {
        // Tapping the notification opens MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Driver Safety")
            .setContentText("Recording driving data…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
