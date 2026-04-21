package com.driversafety.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driversafety.app.data.RecordingStatus
import com.driversafety.app.service.DrivingForegroundService
import com.driversafety.app.ui.theme.*
import com.driversafety.app.viewmodel.DrivingViewModel

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MainActivity.kt
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Single-screen Compose dashboard for the Driver Safety app.
 *
 * ── Layout ─────────────────────────────────────────────────────────────
 * The screen is a single scrollable Column with:
 *   1. Gradient header       — app branding
 *   2. Model badge           — "PyTorch Model Loaded" or "Heuristic"
 *   3. Status card           — IDLE / RECORDING / STOPPED + elapsed time
 *   4. Live rating card      — latest 1-5 star rating from inference
 *   5. Action button         — Start ↔ Stop ↔ New Trip (morphing)
 *   6. Trip summary card     — average rating shown after stopping
 *
 * ── Configuration changes ──────────────────────────────────────────────
 * The AndroidManifest declares configChanges for orientation, so the
 * Activity is not recreated on rotation. The ViewModel also survives
 * config changes naturally.
 *
 * ── Permissions ────────────────────────────────────────────────────────
 * On launch we request:
 *   • ACTIVITY_RECOGNITION (Android 10+)
 *   • POST_NOTIFICATIONS (Android 13+)
 */
class MainActivity : ComponentActivity() {

    // ── Permission launcher ─────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results are logged but non-blocking for core functionality */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request necessary runtime permissions
        requestNeededPermissions()

        setContent {
            DriverSafetyTheme {
                // Obtain the ViewModel (survives configuration changes)
                val viewModel: DrivingViewModel = viewModel()

                // Observe the UI state as Compose State
                val uiState by viewModel.uiState.collectAsState()

                // Render the dashboard
                DriverSafetyDashboard(
                    uiState = uiState,
                    isRealModel = viewModel.isRealModelLoaded,
                    onStart = {
                        viewModel.startRecording()
                        DrivingForegroundService.start(this)
                    },
                    onStop = {
                        viewModel.stopRecording()
                        DrivingForegroundService.stop(this)
                    },
                    onReset = { viewModel.reset() }
                )
            }
        }
    }

    /**
     * Checks which permissions are not yet granted and requests them.
     * The app works fine without them but ACTIVITY_RECOGNITION improves
     * driving context detection and POST_NOTIFICATIONS is needed for
     * the foreground service notification on Android 13+.
     */
    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()

        // Android 10+ requires ACTIVITY_RECOGNITION for motion context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        // Android 13+ requires POST_NOTIFICATIONS for the foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter to only permissions not yet granted
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// ──  COMPOSE UI — Main Dashboard  ──────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════

/**
 * The root composable for the single-screen dashboard.
 *
 * @param uiState    Current UI state from the ViewModel.
 * @param isRealModel Whether the PyTorch model is loaded (vs heuristic).
 * @param onStart    Callback to start recording.
 * @param onStop     Callback to stop recording.
 * @param onReset    Callback to reset back to IDLE.
 */
@Composable
fun DriverSafetyDashboard(
    uiState: com.driversafety.app.data.DrivingUiState,
    isRealModel: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 1. Header ───────────────────────────────────────────────
            HeaderSection()

            Spacer(modifier = Modifier.height(16.dp))

            // ── 2. Model badge ──────────────────────────────────────────
            ModelBadge(isRealModel = isRealModel)

            Spacer(modifier = Modifier.height(20.dp))

            // ── 3. Status card ──────────────────────────────────────────
            StatusCard(status = uiState.status, elapsed = uiState.elapsedSeconds)

            Spacer(modifier = Modifier.height(24.dp))

            // ── 4. Live rating (visible during and after recording) ─────
            AnimatedVisibility(
                visible = uiState.latestRating != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut()
            ) {
                uiState.latestRating?.let { rating ->
                    LiveRatingCard(rating = rating, count = uiState.ratingsCount)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 5. Start / Stop / New Trip button ───────────────────────
            ActionButton(
                status = uiState.status,
                onStart = onStart,
                onStop = onStop,
                onReset = onReset
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── 6. Trip summary (after stop) ────────────────────────────
            AnimatedVisibility(
                visible = uiState.status == RecordingStatus.STOPPED,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                TripSummaryCard(
                    averageRating = uiState.averageRating,
                    totalInferences = uiState.ratingsCount
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// ──  Sub-components  ──────────────────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════

/**
 * Gradient header banner with the app icon, title, and subtitle.
 */
@Composable
fun HeaderSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Teal700, Teal500, Color(0xFF26C6DA)),
                    start = Offset.Zero,
                    end = Offset(800f, 400f)
                )
            )
            .padding(vertical = 28.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Driver Safety",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Real-time driving behavior analysis",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White.copy(alpha = 0.8f)
                )
            )
        }
    }
}

/**
 * Small chip indicating whether the real PyTorch model is loaded
 * or the app is running in heuristic fallback mode.
 */
@Composable
fun ModelBadge(isRealModel: Boolean) {
    // Green chip for real model, orange chip for heuristic
    val (bgColor, label) = if (isRealModel) {
        GreenGood.copy(alpha = 0.15f) to "PyTorch Model Loaded"
    } else {
        OrangeWarn.copy(alpha = 0.15f) to "Heuristic Fallback Mode"
    }
    val textColor = if (isRealModel) GreenGood else OrangeWarn

    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isRealModel) Icons.Filled.CheckCircle
                              else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = textColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = textColor, fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

/**
 * Card showing the current recording status with an animated
 * pulsing indicator dot when recording is active.
 */
@Composable
fun StatusCard(status: RecordingStatus, elapsed: Long) {
    // Map status → display label, color, and icon
    val (statusText, statusColor, icon) = when (status) {
        RecordingStatus.IDLE -> Triple(
            "Ready", TextSecondary, Icons.Rounded.RadioButtonUnchecked
        )
        RecordingStatus.RECORDING -> Triple(
            "Recording", Teal200, Icons.Rounded.FiberManualRecord
        )
        RecordingStatus.STOPPED -> Triple(
            "Trip Complete", Amber400, Icons.Rounded.CheckCircle
        )
    }

    // Pulsing alpha animation — makes the recording dot blink
    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val alpha = if (status == RecordingStatus.RECORDING) pulseAlpha else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status label with animated icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = statusColor.copy(alpha = alpha)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            // Elapsed timer (shown during and after recording)
            if (status == RecordingStatus.RECORDING || status == RecordingStatus.STOPPED) {
                Text(
                    text = formatElapsed(elapsed),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

/**
 * Large card showing the latest star rating from model inference.
 * Stars are colored based on the rating (red=1, green=5).
 */
@Composable
fun LiveRatingCard(rating: Int, count: Int) {
    val ratingColor = ratingToColor(rating)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardDarkAlt)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Live Rating",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = TextSecondary, letterSpacing = 2.sp
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ── Star row ────────────────────────────────────────────────
            Row {
                for (i in 1..5) {
                    val starColor = if (i <= rating) ratingColor else Color(0xFF33334A)
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = starColor
                    )
                    if (i < 5) Spacer(modifier = Modifier.width(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Human-readable label (e.g. "Excellent", "Dangerous")
            Text(
                text = ratingToLabel(rating),
                style = MaterialTheme.typography.titleMedium.copy(
                    color = ratingColor, fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Inference count
            Text(
                text = "Inference #$count",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}

/**
 * Morphing action button that changes label, icon, and color
 * based on the current recording status:
 *   IDLE     → "Start Recording"  (teal, play icon)
 *   RECORDING → "Stop Recording"  (red, stop icon)
 *   STOPPED  → "New Trip"         (teal, refresh icon)
 */
@Composable
fun ActionButton(
    status: RecordingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    val (label, icon, bgColor, action) = when (status) {
        RecordingStatus.IDLE -> ButtonConfig(
            "Start Recording", Icons.Rounded.PlayArrow, Teal500, onStart
        )
        RecordingStatus.RECORDING -> ButtonConfig(
            "Stop Recording", Icons.Rounded.Stop, RedAccent, onStop
        )
        RecordingStatus.STOPPED -> ButtonConfig(
            "New Trip", Icons.Rounded.Refresh, Teal500, onReset
        )
    }

    Button(
        onClick = action,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
    }
}

/**
 * Post-trip summary card showing the average score, star visualization,
 * and session statistics. Only visible after recording stops.
 */
@Composable
fun TripSummaryCard(averageRating: Float?, totalInferences: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1A1A2E),
                            Color(0xFF16213E)
                        )
                    )
                )
                .padding(28.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Section icon
                Icon(
                    imageVector = Icons.Rounded.Assessment,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Amber400
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Section title
                Text(
                    text = "TRIP SUMMARY",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Amber400,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                // ── Big average number ──────────────────────────────────
                val avg = averageRating ?: 0f
                val avgColor = ratingToColor(avg.toInt().coerceIn(1, 5))
                Text(
                    text = String.format("%.1f", avg),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = avgColor
                    )
                )
                Text(
                    text = "out of 5.0",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Star visualization ──────────────────────────────────
                Row {
                    val filled = (avg + 0.5f).toInt().coerceIn(0, 5)
                    for (i in 1..5) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = if (i <= filled) Amber400 else Color(0xFF33334A)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Divider
                Divider(color = Color(0xFF33334A), thickness = 1.dp)

                Spacer(modifier = Modifier.height(12.dp))

                // ── Statistics row ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = "Inferences", value = "$totalInferences")
                    StatItem(
                        label = "Verdict",
                        value = ratingToLabel(avg.toInt().coerceIn(1, 5))
                    )
                }
            }
        }
    }
}

/** A simple stat label + value pair used in the trip summary. */
@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                color = TextPrimary, fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
    }
}


// ════════════════════════════════════════════════════════════════════════════
// ──  Helpers  ─────────────────────────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════

/**
 * Simple data holder for [ActionButton] configuration.
 * Uses destructuring: `val (label, icon, color, action) = config`.
 */
private data class ButtonConfig(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val action: () -> Unit
)

/**
 * Maps a 1-5 integer rating to a semantic color:
 *   1 = Red (dangerous)
 *   2 = Orange
 *   3 = Amber (moderate)
 *   4 = Light green
 *   5 = Green (excellent)
 */
fun ratingToColor(rating: Int): Color = when (rating) {
    1 -> RedAccent
    2 -> OrangeWarn
    3 -> Amber400
    4 -> Color(0xFF9CCC65)    // light green
    5 -> GreenGood
    else -> TextSecondary
}

/**
 * Maps a 1-5 integer rating to a human-readable label.
 */
fun ratingToLabel(rating: Int): String = when (rating) {
    1 -> "Dangerous"
    2 -> "Aggressive"
    3 -> "Moderate"
    4 -> "Good"
    5 -> "Excellent"
    else -> "—"
}

/**
 * Formats elapsed seconds as MM:SS for display.
 */
fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
