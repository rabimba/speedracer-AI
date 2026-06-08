package com.trustableai.koru.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TrackHudMode
import com.trustableai.koru.ui.theme.KoruDimens
import com.trustableai.koru.ui.theme.KoruPalette
import com.trustableai.koru.ui.theme.koruCardBorder
import com.trustableai.koru.ui.theme.trackCueColor
import com.trustableai.koru.ui.theme.trackStatusColor
import java.util.Locale
import kotlinx.coroutines.delay

internal const val TRACK_CUE_MAX_WORDS = 7
internal const val HOLD_STOP_DURATION_MS = 1_500L

internal fun truncateCueWords(text: String, maxWords: Int = TRACK_CUE_MAX_WORDS): String {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return text.trim()
    if (words.size <= maxWords) return words.joinToString(" ")
    return words.take(maxWords).joinToString(" ") + "…"
}

internal data class TrackModeCue(
    val title: String?,
    val subtitle: String?,
    val priority: Int?,
    val signalOnly: Boolean,
)

internal fun SessionUiState.trackModeCue(): TrackModeCue {
    val decision = decisions.lastOrNull()
    val signalOnly = trackHudMode == TrackHudMode.SIGNAL_ONLY
    return if (decision == null) {
        TrackModeCue(
            title = if (signalOnly) null else if (isSessionActive) "Coach standing by" else "Ready when you are",
            subtitle = null,
            priority = null,
            signalOnly = signalOnly,
        )
    } else {
        TrackModeCue(
            title = if (signalOnly) null else truncateCueWords(decision.text),
            subtitle = if (signalOnly) null else {
                "P${decision.priority} ${decision.path.name} ${decision.action?.name ?: "cue"} ${decision.cornerPhase.name}"
            },
            priority = decision.priority,
            signalOnly = signalOnly,
        )
    }
}

@Composable
internal fun TrackModeScreen(
    state: SessionUiState,
    onMuteToggle: () -> Unit,
    onStopRequested: () -> Unit,
) {
    val cue = state.trackModeCue()
    val statusColor by animateColorAsState(
        targetValue = trackStatusColor(state.backendStatus.state),
        animationSpec = tween(durationMillis = 400),
        label = "backend-status-color",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("track-mode")
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        KoruPalette.BackgroundDeep,
                        KoruPalette.BackgroundMid,
                        KoruPalette.BackgroundDeep,
                    ),
                ),
            )
            .padding(KoruDimens.ScreenPadding),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(KoruDimens.SectionSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "TRACK MODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.trackName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TrackStatusPill(
                    text = state.backendStatus.state.name.lowercase(Locale.US),
                    color = statusColor,
                    icon = trackStatusIcon(state.backendStatus.state),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                trackCueColor(cue.priority).copy(alpha = if (cue.signalOnly) 0.14f else 0.08f),
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                                MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            radius = if (cue.signalOnly) 900f else 600f,
                        ),
                        RoundedCornerShape(KoruDimens.CardRadius),
                    ),
                contentAlignment = if (cue.signalOnly) Alignment.Center else Alignment.TopStart,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(KoruDimens.CardPadding),
                    verticalArrangement = if (cue.signalOnly) {
                        Arrangement.Center
                    } else {
                        Arrangement.SpaceBetween
                    },
                    horizontalAlignment = if (cue.signalOnly) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    TrackSignalLight(
                        priority = cue.priority,
                        backendState = state.backendStatus.state,
                        large = cue.signalOnly,
                    )
                    if (!cue.title.isNullOrBlank() || !cue.subtitle.isNullOrBlank()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            cue.title?.let { title ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            cue.subtitle?.let { subtitle ->
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            TrackModeGaugeStrip(frame = state.latestFrame)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = KoruDimens.MinTouchTarget)
                        .testTag("mute-session")
                        .semantics {
                            contentDescription = if (state.audioEnabled) {
                                "Mute coaching audio"
                            } else {
                                "Unmute coaching audio"
                            }
                        },
                    shape = RoundedCornerShape(KoruDimens.ChipRadius),
                ) {
                    Icon(
                        imageVector = if (state.audioEnabled) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.audioEnabled) "Mute" else "Unmute")
                }
                HoldStopButton(
                    modifier = Modifier.weight(1f),
                    onStopRequested = onStopRequested,
                )
            }
        }
    }
}

@Composable
internal fun HoldStopButton(
    modifier: Modifier = Modifier,
    onStopRequested: () -> Unit,
) {
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(2_000)
            showHint = false
        }
    }

    Surface(
        modifier = modifier
            .sizeIn(minHeight = KoruDimens.MinTouchTarget)
            .testTag("hold-stop-session")
            .semantics { contentDescription = "Hold to stop session" }
            .pointerInput(onStopRequested) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    var stopTriggered = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) {
                            if (!stopTriggered) {
                                showHint = true
                            }
                            break
                        }
                        if (!stopTriggered && System.currentTimeMillis() - startedAt >= HOLD_STOP_DURATION_MS) {
                            stopTriggered = true
                            onStopRequested()
                        }
                    }
                }
            },
        shape = RoundedCornerShape(KoruDimens.ChipRadius),
        color = KoruPalette.HoldStopSurface,
        contentColor = KoruPalette.HoldStopContent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hold to Stop", fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (showHint) "Hold 1.5s to stop" else "Stop Session",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoruPalette.HoldStopContent.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
internal fun TrackModeGaugeStrip(frame: TelemetryFrame?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrackMetricTile(
            label = "Speed",
            value = frame?.speedMph?.let { "%.0f".format(Locale.US, it) } ?: "--",
            unit = "mph",
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Speed,
            contentDescription = frame?.speedMph?.let { "Speed %.0f miles per hour".format(Locale.US, it) },
            emphasized = true,
        )
        TrackMetricTile(
            label = "Lat G",
            value = frame?.gLat?.let { "%.2f".format(Locale.US, it) } ?: "--",
            unit = "g",
            modifier = Modifier.weight(1f),
        )
        TrackMetricTile(
            label = "Brake",
            value = frame?.brake?.let { "%.0f".format(Locale.US, it * 100.0) } ?: "--",
            unit = "%",
            modifier = Modifier.weight(1f),
        )
        TrackMetricTile(
            label = "Throttle",
            value = frame?.throttle?.let { "%.0f".format(Locale.US, it * 100.0) } ?: "--",
            unit = "%",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrackMetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = modifier
            .sizeIn(minHeight = 52.dp)
            .koruCardBorder(RoundedCornerShape(KoruDimens.ChipRadius))
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(KoruDimens.ChipRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = value,
                    style = if (emphasized) {
                        MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp)
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (value != "--") {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrackSignalLight(
    priority: Int?,
    backendState: LiveBackendState,
    large: Boolean = false,
) {
    val signalColor by animateColorAsState(
        targetValue = when {
            priority == 0 || priority == 1 -> KoruPalette.SignalUrgent
            priority == 2 || priority == 3 -> KoruPalette.SignalAdvisory
            backendState == LiveBackendState.READY || backendState == LiveBackendState.DEGRADED -> KoruPalette.SignalReady
            else -> KoruPalette.SignalNeutral
        },
        animationSpec = tween(durationMillis = 350),
        label = "signal-color",
    )
    val description = when {
        priority == 0 || priority == 1 -> "Urgent coaching signal"
        priority == 2 || priority == 3 -> "Advisory coaching signal"
        backendState == LiveBackendState.READY || backendState == LiveBackendState.DEGRADED -> "Coach ready"
        else -> "Coach standing by"
    }
    val size = if (large) 132.dp else 72.dp
    val iconSize = if (large) 56.dp else 32.dp
    val pulseTransition = rememberInfiniteTransition(label = "signal-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = if (priority == 0) 0.42f else 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (priority == 0) 700 else 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (priority == 0) 1.18f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (priority == 0) 700 else 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    Box(
        modifier = Modifier.semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * pulseScale * 1.35f)
                .blur(28.dp)
                .background(signalColor.copy(alpha = pulseAlpha), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            signalColor.copy(alpha = 1f),
                            signalColor.copy(alpha = 0.82f),
                            signalColor.copy(alpha = 0.65f),
                        ),
                    ),
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = trackPriorityIcon(priority),
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun TrackStatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(KoruDimens.PillRadius),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun trackPriorityIcon(priority: Int?): ImageVector = when (priority) {
    0, 1 -> Icons.Filled.Error
    2, 3 -> Icons.Filled.Warning
    else -> Icons.Filled.CheckCircle
}

internal fun trackStatusIcon(state: LiveBackendState): ImageVector = when (state) {
    LiveBackendState.READY -> Icons.Filled.CheckCircle
    LiveBackendState.DEGRADED -> Icons.Filled.Warning
    LiveBackendState.ERROR, LiveBackendState.UNAVAILABLE -> Icons.Filled.Error
    LiveBackendState.STARTING -> Icons.Filled.Sensors
    LiveBackendState.IDLE -> Icons.Filled.Info
}

internal fun sensorTrustLabel(health: TelemetrySourceHealth?): String {
    if (health == null) return "waiting"
    if (health.degradedReason != null || health.signUnverified) return "degraded"
    if (health.canConnected || health.raceBoxConnected || health.obdConnected || health.motionConnected == true) {
        return "trusted"
    }
    return health.status.ifBlank { "waiting" }
}
