package com.trustableai.koru.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trustableai.koru.model.AimCanBitrate
import com.trustableai.koru.model.CanVehicleDiagnostics
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.EdgeInferenceMetrics
import com.trustableai.koru.model.LiveBackendState
import com.trustableai.koru.model.LiveBackendStatus
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.RuntimeAccelerator
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.TelemetrySourceHealth
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.TrackHudMode
import com.trustableai.koru.runtime.TrackCatalog
import com.trustableai.koru.ui.theme.KoruDimens
import com.trustableai.koru.ui.theme.KoruPalette
import com.trustableai.koru.ui.theme.KoruTheme
import com.trustableai.koru.ui.theme.koruCardBorder
import com.trustableai.koru.ui.theme.trackCueColor
import java.util.Locale

private enum class KoruDestination(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String,
) {
    PREVIEW("Preview", Icons.Filled.DirectionsCar, "Session preview"),
    SETUP("Setup", Icons.Filled.Settings, "Session setup"),
    REPLAY("Replay", Icons.Filled.PlayArrow, "Saved session replay"),
    PADDOCK("Paddock", Icons.Filled.Analytics, "Paddock review"),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Build, "Diagnostics"),
}

private data class PrimaryCue(
    val title: String,
    val subtitle: String,
    val priority: Int?,
    val timestampMs: Long?,
)

private data class ReadinessStatus(
    val label: String,
    val detail: String,
    val icon: ImageVector,
    val color: Color,
)

private val SessionUiState.isTrackModeActive: Boolean
    get() = isSessionActive

private val SessionUiState.canEditSetup: Boolean
    get() = !isSessionActive

private fun SessionUiState.primaryCue(): PrimaryCue {
    val decision = decisions.lastOrNull()
    return if (decision == null) {
        PrimaryCue(
            title = if (isSessionActive) "Coach standing by" else "Ready when you are",
            subtitle = if (isSessionActive) "Waiting for first telemetry trigger" else "Complete setup before rolling out",
            priority = null,
            timestampMs = null,
        )
    } else {
        PrimaryCue(
            title = decision.text,
            subtitle = "P${decision.priority} ${decision.path.name} ${decision.action?.name ?: "cue"} ${decision.cornerPhase.name}",
            priority = decision.priority,
            timestampMs = decision.timestampMs,
        )
    }
}

private fun SessionUiState.readinessStatus(): ReadinessStatus {
    return when (backendStatus.state) {
        LiveBackendState.READY ->
            ReadinessStatus("Ready", backendStatus.detail, Icons.Filled.CheckCircle, KoruPalette.SignalReady)
        LiveBackendState.DEGRADED ->
            ReadinessStatus("Degraded", backendStatus.detail, Icons.Filled.Warning, KoruPalette.SignalAdvisory)
        LiveBackendState.ERROR, LiveBackendState.UNAVAILABLE ->
            ReadinessStatus("Action needed", backendStatus.detail, Icons.Filled.Error, KoruPalette.Error)
        LiveBackendState.STARTING ->
            ReadinessStatus("Starting", backendStatus.detail, Icons.Filled.Sensors, KoruPalette.SignalInfo)
        LiveBackendState.IDLE ->
            ReadinessStatus("Idle", backendStatus.detail, Icons.Filled.Info, KoruPalette.SignalNeutral)
    }
}

@Composable
fun KoruApp(
    viewModel: LiveSessionViewModel,
    onStartRequested: () -> Unit,
    onBindCameraPreview: (PreviewView) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.requestBackendStatus()
    }

    LaunchedEffect(state.primaryCue().timestampMs, state.primaryCue().priority) {
        if (state.primaryCue().priority == 0) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(state.backendStatus.state) {
        if (state.backendStatus.state == LiveBackendState.ERROR) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    KoruTheme {
        if (state.isTrackModeActive) {
            TrackModeScreen(
                state = state,
                onMuteToggle = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.setAudioEnabled(!state.audioEnabled)
                },
                onStopRequested = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.stopSession()
                },
            )
            return@KoruTheme
        }

        var destination by rememberSaveable { mutableStateOf(KoruDestination.SETUP) }
        val visibleDestinations = visibleDestinations(state)
        LaunchedEffect(state.savedSession?.id, state.isTrackModeActive) {
            if (!state.isTrackModeActive && state.savedSession != null) {
                destination = KoruDestination.PADDOCK
            }
        }
        LaunchedEffect(visibleDestinations, destination) {
            if (destination !in visibleDestinations) {
                destination = KoruDestination.SETUP
            }
        }

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                visibleDestinations.forEach { item ->
                    item(
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = item.contentDescription,
                                modifier = Modifier.testTag("destination-${item.name.lowercase(Locale.US)}"),
                            )
                        },
                        label = { Text(item.label) },
                        selected = destination == item,
                        onClick = { destination = item },
                    )
                }
            },
        ) {
            AppBackground {
                when (destination) {
                    KoruDestination.PREVIEW -> PreviewScreen(state)
                    KoruDestination.SETUP -> SetupScreen(
                        state = state,
                        viewModel = viewModel,
                        onStartRequested = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onStartRequested()
                        },
                    )
                    KoruDestination.REPLAY -> ReplayScreen(
                        state = state,
                        viewModel = viewModel,
                    )
                    KoruDestination.PADDOCK -> PaddockScreen(state)
                    KoruDestination.DIAGNOSTICS -> DiagnosticsScreen(
                        state = state,
                        onBindCameraPreview = onBindCameraPreview,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        KoruPalette.BackgroundDeep,
                        KoruPalette.BackgroundMid,
                        KoruPalette.BackgroundGlow,
                        KoruPalette.BackgroundDeep,
                    ),
                ),
            ),
    ) {
        content()
    }
}

@Composable
private fun ScreenColumn(
    tag: String,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        contentPadding = PaddingValues(KoruDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(KoruDimens.SectionSpacing),
    ) {
        item { content() }
    }
}

private fun visibleDestinations(state: SessionUiState): List<KoruDestination> {
    return buildList {
        if (state.isSessionActive || state.decisions.isNotEmpty() || state.latestFrame != null) {
            add(KoruDestination.PREVIEW)
        }
        add(KoruDestination.SETUP)
        if (state.savedSessions.isNotEmpty() || state.savedSession != null) {
            add(KoruDestination.REPLAY)
        }
        add(KoruDestination.PADDOCK)
        add(KoruDestination.DIAGNOSTICS)
    }
}

@Composable
private fun PreviewScreen(state: SessionUiState) {
    ScreenColumn("preview-screen") {
        ScreenHeader(
            eyebrow = "Preview",
            title = if (state.isSessionActive) "Live session preview" else "Start session for Track Mode",
            meta = state.backendStatus.state.name.lowercase(Locale.US),
        )
        if (!state.isSessionActive && state.decisions.isEmpty()) {
            Text(
                text = "Track Mode takes over once the session is live. Use this preview after telemetry starts flowing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CueSurface(state = state, compact = false)
            TelemetryStrip(
                frame = state.latestFrame,
                health = state.latestFrame?.sourceHealth,
                audioEnabled = state.audioEnabled,
            )
            if (state.decisions.isNotEmpty()) {
                DecisionHistory(decisions = state.decisions.takeLast(5).reversed())
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: SessionUiState,
    viewModel: LiveSessionViewModel,
    onStartRequested: () -> Unit,
) {
    ScreenColumn("setup-screen") {
        ScreenHeader(
            eyebrow = "Setup",
            title = "Session Initialization",
            meta = "${viewModel.sessionGoals().size}/3 goals",
        )
        SetupProgressIndicator(completedSteps = 4, totalSteps = 4)
        ReadinessPanel(state.readinessStatus())
        SetupStep(number = 1, title = "Session and source") {
            OptionRow(
                label = "Mode",
                options = listOf(
                    SessionMode.TELEMETRY to "Telemetry + Map",
                    SessionMode.CAN_INTERFACE_CHECK to "CAN Interface Check",
                    SessionMode.DEVICE_TEST to "Device Test",
                    SessionMode.CAMERA_DIRECT to "Camera Feedback",
                ),
                selected = state.sessionMode,
                enabled = state.canEditSetup,
                onSelected = viewModel::setSessionMode,
            )
            if (state.sessionMode == SessionMode.TELEMETRY) {
                OptionRow(
                    label = "Source",
                    options = listOf(
                        TelemetrySourceKind.AIM_CAN_USB to "AiM CAN USB",
                        TelemetrySourceKind.RACEBOX_OBD_FUSION to "RaceBox + OBDLink",
                        TelemetrySourceKind.PHONE_IMU_GPS to "Phone IMU + GPS",
                        TelemetrySourceKind.SYNTHETIC to "Synthetic",
                        TelemetrySourceKind.RACEBOX_BLE to "RaceBox BLE",
                        TelemetrySourceKind.OBD_BLUETOOTH to "OBD Bluetooth",
                    ),
                    selected = state.telemetrySource,
                    enabled = state.canEditSetup,
                    onSelected = viewModel::setTelemetrySource,
                )
                if (state.telemetrySource == TelemetrySourceKind.RACEBOX_OBD_FUSION) {
                    OptionRow(
                        label = "OBD",
                        options = listOf(
                            ObdTransportPreference.AUTO to "Auto",
                            ObdTransportPreference.BLUETOOTH to "Bluetooth MX+",
                            ObdTransportPreference.USB to "USB EX",
                        ),
                        selected = state.obdTransportPreference,
                        enabled = state.canEditSetup,
                        onSelected = viewModel::setObdTransportPreference,
                    )
                }
                if (state.telemetrySource == TelemetrySourceKind.AIM_CAN_USB) {
                    AimCanBitrateRow(state, viewModel)
                }
                OptionRow(
                    label = "Vision",
                    options = listOf(
                        false to "Map Only",
                        true to "Camera Fusion",
                    ),
                    selected = state.cameraFusionEnabled,
                    enabled = state.canEditSetup,
                    onSelected = viewModel::setCameraFusionEnabled,
                )
            } else if (state.sessionMode == SessionMode.CAN_INTERFACE_CHECK) {
                AimCanBitrateRow(state, viewModel)
            }
        }
        SetupStep(number = 2, title = "Track and focus") {
            OptionRow(
                label = "Track",
                options = listOf(
                    TrackCatalog.sonomaRaceway.name to "Sonoma",
                    TrackCatalog.thunderhillEast.name to "Thunderhill",
                ),
                selected = state.trackName,
                enabled = state.canEditSetup && state.sessionMode != SessionMode.DEVICE_TEST,
                onSelected = viewModel::setTrackName,
            )
            GoalSelector(state, viewModel)
        }
        SetupStep(number = 3, title = "Coach, audio, and HUD") {
            CoachSelector(state, viewModel)
            AudioControlRow(state = state, viewModel = viewModel)
            TrackHudModeRow(state = state, viewModel = viewModel)
        }
        SetupStep(number = 4, title = "Roll out") {
            Text(
                text = "Track Mode starts automatically once the session is live.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onStartRequested,
                enabled = !state.isSessionActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = KoruDimens.MinTouchTarget)
                    .testTag("setup-start-session"),
                shape = RoundedCornerShape(KoruDimens.ChipRadius),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start Session")
            }
        }
    }
}

@Composable
private fun PaddockScreen(state: SessionUiState) {
    ScreenColumn("paddock-screen") {
        ScreenHeader(
            eyebrow = "Paddock",
            title = "Review and sync",
            meta = state.savedSession?.summary?.trackName ?: "no session",
        )
        LearningPlanPanel(state)
        SavedSessionPanel(state)
        SyncPanel(state)
        DecisionHistory(decisions = state.decisions.takeLast(6).reversed())
    }
}

@Composable
private fun ReplayScreen(state: SessionUiState, viewModel: LiveSessionViewModel) {
    val sessions = state.savedSessions.ifEmpty {
        state.savedSession?.let { listOf(it) } ?: emptyList()
    }
    val selected = sessions.firstOrNull { it.id == state.replaySessionId } ?: sessions.firstOrNull()
    val maxIndex = selected?.frames?.lastIndex?.coerceAtLeast(0) ?: 0
    val replayIndex = state.replayFrameIndex.coerceIn(0, maxIndex)
    val frame = selected?.frames?.getOrNull(replayIndex)
    ScreenColumn("replay-screen") {
        ScreenHeader(
            eyebrow = "Replay",
            title = "Saved Sessions",
            meta = selected?.endedReason ?: "none",
        )
        if (sessions.isEmpty()) {
            WorkSurface(
                title = "Saved Sessions",
                meta = "empty",
                tag = "replay-empty",
            ) {
                Text(
                    text = "Start and stop a telemetry session to create a replayable field artifact.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@ScreenColumn
        }
        WorkSurface(
            title = "Session Picker",
            meta = "${sessions.size}",
            tag = "replay-session-picker",
        ) {
            ChipFlow {
                sessions.forEach { session ->
                    FilterChip(
                        selected = session.id == selected?.id,
                        onClick = { viewModel.selectReplaySession(session.id) },
                        label = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = session.trackName,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${session.totalFrameCount} frames ${session.endedReason}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        },
                        shape = RoundedCornerShape(KoruDimens.ChipRadius),
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    )
                }
            }
        }
        selected?.let { session ->
            WorkSurface(
                title = "Timeline",
                meta = "${replayIndex + 1}/${session.frames.size.coerceAtLeast(1)}",
                tag = "replay-timeline",
            ) {
                TrackMapOverlay(trackName = session.trackName, frame = frame)
                TrackModeGaugeStrip(frame = frame)
                Slider(
                    value = replayIndex.toFloat(),
                    onValueChange = { value -> viewModel.setReplayFrameIndex(value.toInt()) },
                    valueRange = 0f..maxIndex.coerceAtLeast(1).toFloat(),
                    steps = if (maxIndex > 1) maxIndex - 1 else 0,
                    enabled = maxIndex > 0,
                    modifier = Modifier.testTag("replay-scrubber"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::toggleReplay,
                        enabled = session.frames.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = KoruDimens.MinTouchTarget)
                            .testTag("replay-play-pause"),
                        shape = RoundedCornerShape(KoruDimens.ChipRadius),
                    ) {
                        Icon(if (state.replayPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.replayPlaying) "Pause" else "Play")
                    }
                    OutlinedButton(
                        onClick = { viewModel.setReplayFrameIndex(0) },
                        enabled = session.frames.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = KoruDimens.MinTouchTarget),
                        shape = RoundedCornerShape(KoruDimens.ChipRadius),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset")
                    }
                }
                Text(
                    text = frame?.let {
                        "t=${"%.1f".format(Locale.US, it.timeSeconds)}s speed=${"%.0f".format(Locale.US, it.speedMph)}mph brake=${"%.0f".format(Locale.US, it.brake)} throttle=${"%.0f".format(Locale.US, it.throttle)} rpm=${it.rpm ?: "--"}"
                    } ?: "This manifest has sidecars but no embedded preview frames. Pull frames.ndjson for full replay analysis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WorkSurface(
                title = "Replay Evidence",
                meta = "sidecars",
                tag = "replay-evidence",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricTile("Embedded", "${session.embeddedFrameCount}", Modifier.weight(1f))
                    MetricTile("Total", "${session.totalFrameCount}", Modifier.weight(1f))
                    MetricTile("Audio", "${session.audioEvents.size}", Modifier.weight(1f))
                }
                Text(
                    text = listOfNotNull(
                        session.artifactPath?.let { "session.json: $it" },
                        session.framesPath?.let { "frames.ndjson: $it" },
                        session.decisionsPath?.let { "decisions.ndjson: $it" },
                        session.audioEventsPath?.let { "audio-events.ndjson: $it" },
                        session.canDumpPath?.let { "can-slcan.txt: $it" },
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.audioEvents.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        session.audioEvents.takeLast(5).reversed().forEach { event ->
                            Text(
                                text = "${event.scope.name} ${event.status.name} ${event.clipName ?: event.action?.name ?: event.fallbackReason.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    state: SessionUiState,
    onBindCameraPreview: (PreviewView) -> Unit,
) {
    ScreenColumn("diagnostics-screen") {
        ScreenHeader(
            eyebrow = "Diagnostics",
            title = "Hardware and model health",
            meta = state.backendStatus.backend.name.lowercase(Locale.US),
        )
        BackendDetailPanel(state)
        AcceleratorReadinessPanel(state.backendStatus)
        AimCanTestPanel(state)
        if (state.cameraFusionEnabled || state.sessionMode == SessionMode.CAMERA_DIRECT) {
            CameraPanel(
                cameraStatus = state.cameraStatus,
                onBindCameraPreview = onBindCameraPreview,
            )
        } else {
            CameraDisabledPanel()
        }
        EdgeInferenceMetricsPanel(
            metrics = state.edgeInferenceMetrics,
            backendStatus = state.backendStatus,
        )
    }
}

@Composable
private fun SetupProgressIndicator(completedSteps: Int, totalSteps: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SETUP PROGRESS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$completedSteps of $totalSteps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { completedSteps.toFloat() / totalSteps.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            (1..totalSteps).forEach { step ->
                SetupStepDot(step = step, active = step <= completedSteps)
            }
        }
    }
}

@Composable
private fun SetupStepDot(step: Int, active: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Text(
                text = step.toString(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScreenHeader(
    eyebrow: String,
    title: String,
    meta: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius)),
        shape = RoundedCornerShape(KoruDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(KoruDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier
                    .width(4.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(KoruDimens.PillRadius),
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = eyebrow.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            StatusPill(meta, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Info)
        }
    }
}

@Composable
private fun CueSurface(
    state: SessionUiState,
    compact: Boolean,
) {
    val cue = state.primaryCue()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live-hud")
            .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius)),
        shape = RoundedCornerShape(KoruDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(KoruDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TrackSignalLight(cue.priority, state.backendStatus.state)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = cue.title,
                        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cue.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { ((state.latestFrame?.speedMph ?: 0.0) / 120.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = trackCueColor(cue.priority),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun TelemetryStrip(
    frame: TelemetryFrame?,
    health: TelemetrySourceHealth?,
    audioEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile("Speed", frame?.speedMph?.let { "%.0f mph".format(Locale.US, it) } ?: "--", Modifier.weight(1f), Icons.Filled.Speed)
        MetricTile("Audio", if (audioEnabled) "on" else "muted", Modifier.weight(1f), Icons.Filled.GraphicEq)
        MetricTile("Sensor", sensorTrustLabel(health), Modifier.weight(1f), Icons.Filled.Sensors)
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier
            .sizeIn(minHeight = 72.dp)
            .koruCardBorder(RoundedCornerShape(KoruDimens.ChipRadius)),
        shape = RoundedCornerShape(KoruDimens.ChipRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReadinessPanel(status: ReadinessStatus) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius)),
        shape = RoundedCornerShape(KoruDimens.CardRadius),
        color = status.color.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(KoruDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = status.color.copy(alpha = 0.18f),
            ) {
                Icon(
                    status.icon,
                    contentDescription = null,
                    tint = status.color,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius)),
        shape = RoundedCornerShape(KoruDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(KoruDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = number.toString(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun GoalSelector(state: SessionUiState, viewModel: LiveSessionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Session Goals",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipFlow {
            viewModel.goalOptions.forEach { option ->
                FilterChip(
                    selected = option.focus in state.selectedGoalFocuses,
                    onClick = { viewModel.toggleGoal(option.focus) },
                    enabled = state.canEditSetup,
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(option.label, maxLines = 1, fontWeight = FontWeight.Medium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    shape = RoundedCornerShape(KoruDimens.ChipRadius),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("goal-${option.focus.name.lowercase(Locale.US)}"),
                )
            }
        }
        if (SessionGoalFocus.CUSTOM in state.selectedGoalFocuses) {
            OutlinedTextField(
                value = state.customGoalDescription,
                onValueChange = viewModel::setCustomGoalDescription,
                enabled = state.canEditSetup,
                label = { Text("Custom focus") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun TrackHudModeRow(state: SessionUiState, viewModel: LiveSessionViewModel) {
    OptionRow(
        label = "Track HUD",
        options = listOf(
            TrackHudMode.SIGNAL_ONLY to "Signal only",
            TrackHudMode.SIGNAL_PLUS_TEXT to "Signal + text",
        ),
        selected = state.trackHudMode,
        enabled = state.canEditSetup,
        onSelected = viewModel::setTrackHudMode,
    )
}

@Composable
private fun CoachSelector(state: SessionUiState, viewModel: LiveSessionViewModel) {
    val recommendedCoachId = viewModel.recommendedCoachId()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Coach",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        "Recommended",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledLeadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = null,
                shape = RoundedCornerShape(KoruDimens.ChipRadius),
            )
        }
        ChipFlow {
            viewModel.coachOptions.forEach { coach ->
                FilterChip(
                    selected = coach.id == state.activeCoachId,
                    onClick = { viewModel.setActiveCoach(coach.id) },
                    enabled = state.canEditSetup,
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                coach.name,
                                maxLines = 1,
                                fontWeight = if (coach.id == recommendedCoachId) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                coach.style,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    shape = RoundedCornerShape(KoruDimens.ChipRadius),
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag("coach-${coach.id}"),
                )
            }
        }
    }
}

@Composable
private fun AudioControlRow(state: SessionUiState, viewModel: LiveSessionViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .koruCardBorder(RoundedCornerShape(KoruDimens.ChipRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(KoruDimens.ChipRadius))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.audioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Audio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(
                        state.lastAudioEvent?.let { "Last: ${it.status.name.lowercase(Locale.US)} ${it.clipName ?: it.fallbackReason.orEmpty()}" }
                            ?: if (state.audioEnabled) "Cues will play in Track Mode" else "Cues are muted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = state.audioEnabled,
                onCheckedChange = viewModel::setAudioEnabled,
                modifier = Modifier.testTag("audio-toggle"),
            )
        }
        OutlinedButton(
            onClick = viewModel::playAudioCheck,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = KoruDimens.MinTouchTarget)
                .testTag("audio-check"),
            shape = RoundedCornerShape(KoruDimens.ChipRadius),
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Audio Check")
        }
    }
}

@Composable
private fun AimCanBitrateRow(state: SessionUiState, viewModel: LiveSessionViewModel) {
    OptionRow(
        label = "CAN bitrate",
        options = listOf(
            AimCanBitrate.S8_1MBPS to "S8 1M",
            AimCanBitrate.S6_500KBPS to "S6 500k",
        ),
        selected = state.aimCanBitrate,
        enabled = state.canEditSetup,
        onSelected = viewModel::setAimCanBitrate,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun CameraPanel(
    cameraStatus: String,
    onBindCameraPreview: (PreviewView) -> Unit,
) {
    WorkSurface(
        title = "Camera Lane",
        meta = "CameraX",
        tag = "camera-panel",
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(KoruDimens.ChipRadius)),
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    onBindCameraPreview(this)
                }
            },
            update = { previewView -> onBindCameraPreview(previewView) },
        )
        Text(
            text = cameraStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CameraDisabledPanel() {
    WorkSurface(
        title = "Camera Lane",
        meta = "off",
        tag = "camera-panel-disabled",
    ) {
        Text(
            text = "Camera fusion is off for this setup. Telemetry coaching is using GPS/CAN and the selected track map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EdgeInferenceMetricsPanel(
    metrics: EdgeInferenceMetrics?,
    backendStatus: LiveBackendStatus,
) {
    WorkSurface(
        title = "On-Device LLM",
        meta = backendStatus.backend.name.lowercase(Locale.US),
        tag = "edge-inference-metrics",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = "Speed",
                value = metrics?.let { "%.1f tok/s".format(Locale.US, it.tokensPerSecond) } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Output",
                value = metrics?.let { "${it.outputTokens} tok" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Latency",
                value = metrics?.let { "${it.latencyMs} ms" } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = metrics?.let {
                val prompt = it.promptTokens?.let { promptTokens -> "prompt $promptTokens tok | " } ?: ""
                val trigger = it.triggerId?.let { triggerId -> "trigger $triggerId | " } ?: ""
                "${prompt}${trigger}last EDGE inference on ${it.backend.name}"
            } ?: if (backendStatus.usesOnDeviceModel) {
                "Waiting for the first EDGE inference from ${backendStatus.model ?: backendStatus.backend.name}."
            } else {
                "Stage a LiteRT-LM model to measure on-device token speed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackendDetailPanel(state: SessionUiState) {
    WorkSurface(
        title = "Backend Status",
        meta = state.backendStatus.state.name.lowercase(Locale.US),
        tag = "backend-status-panel",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Backend", backendDisplayName(state.backendStatus.backend), Modifier.weight(1f))
            MetricTile("Model", state.backendStatus.model ?: "--", Modifier.weight(1f))
            MetricTile("Accelerator", acceleratorDisplayName(state.backendStatus.accelerator), Modifier.weight(1f))
        }
        Text(
            text = state.backendStatus.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun backendDisplayName(backend: RuntimeBackend): String =
    when (backend) {
        RuntimeBackend.BROWSER -> "browser"
        RuntimeBackend.AICORE -> "AICore"
        RuntimeBackend.LITERTLM -> "LiteRT"
        RuntimeBackend.DETERMINISTIC -> "fallback"
    }

private fun acceleratorDisplayName(accelerator: RuntimeAccelerator): String =
    when (accelerator) {
        RuntimeAccelerator.NONE -> "none"
        RuntimeAccelerator.MEDIAPIPE_LITERT -> "LiteRT"
        RuntimeAccelerator.AICORE -> "AICore"
        RuntimeAccelerator.UNKNOWN -> "unknown"
    }

@Composable
private fun AcceleratorReadinessPanel(backendStatus: LiveBackendStatus) {
    WorkSurface(
        title = "Accelerator Comparison",
        meta = "CPU / GPU / NPU",
        tag = "accelerator-comparison-panel",
    ) {
        AcceleratorStatusRow(
            label = "CPU",
            status = "Fallback ready",
            detail = "Deterministic HOT/P0 path remains available even when model token generation is blocked.",
            color = KoruPalette.SignalReady,
            icon = Icons.Filled.Speed,
        )
        AcceleratorStatusRow(
            label = "GPU",
            status = when {
                backendStatus.backend == RuntimeBackend.LITERTLM && backendStatus.state == LiveBackendState.READY -> "Token lane ready"
                backendStatus.detail.contains("unsupported", ignoreCase = true) -> "Model blocked"
                else -> "Probe required"
            },
            detail = when {
                backendStatus.backend == RuntimeBackend.LITERTLM && backendStatus.state == LiveBackendState.READY ->
                    "MediaPipe LiteRT is serving EDGE token generation."
                backendStatus.detail.contains("unsupported", ignoreCase = true) ->
                    "Current LiteRT-LM artifact is rejected before native MediaPipe startup."
                else ->
                    "Run the accelerator comparison test with a compatible model staged on device."
            },
            color = if (backendStatus.backend == RuntimeBackend.LITERTLM && backendStatus.state == LiveBackendState.READY) {
                KoruPalette.SignalReady
            } else {
                KoruPalette.SignalAdvisory
            },
            icon = Icons.Filled.GraphicEq,
        )
        AcceleratorStatusRow(
            label = "NPU",
            status = if (backendStatus.backend == RuntimeBackend.AICORE) "AICore detected" else "Status only",
            detail = if (backendStatus.backend == RuntimeBackend.AICORE) {
                "AICore SDK is present, but token-speed benchmarking still needs Prompt API integration."
            } else {
                "This build records AICore availability; it does not expose NPU token generation yet."
            },
            color = if (backendStatus.backend == RuntimeBackend.AICORE) KoruPalette.SignalReady else KoruPalette.SignalNeutral,
            icon = Icons.Filled.Sensors,
        )
    }
}

@Composable
private fun AcceleratorStatusRow(
    label: String,
    status: String,
    detail: String,
    color: Color,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KoruDimens.ChipRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.16f), contentColor = color) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                StatusPill(status, color)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AimCanTestPanel(state: SessionUiState) {
    val frame = state.latestFrame
    val health = frame?.sourceHealth
    val diagnostics = frame?.canVehicleDiagnostics
    val shouldShow = state.telemetrySource == TelemetrySourceKind.AIM_CAN_USB ||
        state.sessionMode == SessionMode.CAN_INTERFACE_CHECK ||
        frame?.telemetrySource == TelemetrySourceKind.AIM_CAN_USB
    if (!shouldShow) return
    val connected = health?.canConnected == true
    val waitingForFrames = health?.canWaitingForFrames == true || (connected && health.rawCanSample == null)

    WorkSurface(
        title = if (state.sessionMode == SessionMode.CAN_INTERFACE_CHECK) "CAN Interface Check" else "AiM CAN USB Test",
        meta = health?.fallbackStage ?: "idle",
        tag = "aim-can-test-panel",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("USB", if (connected) "connected" else "waiting", Modifier.weight(1f))
            MetricTile("Frames", if (waitingForFrames) "waiting" else if (health?.rawCanSample != null) "live" else "--", Modifier.weight(1f))
            MetricTile("Errors", "${health?.canDecodeErrors ?: 0}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Bitrate", health?.canBitrate ?: state.aimCanBitrate.label, Modifier.weight(1f))
            MetricTile("Motion", health?.motionSource ?: "--", Modifier.weight(1f))
            MetricTile("IDs", "${health?.rawCanSamplesById?.size ?: 0}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("RPM", frame?.rpm?.toString() ?: "--", Modifier.weight(1f))
            MetricTile(
                "Pedal",
                diagnostics?.pedalPositionPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "--",
                Modifier.weight(1f),
            )
            MetricTile(
                "Brake PSI",
                diagnostics?.brakePressureCalibratedPsi?.let { "%.1f cal".format(Locale.US, it) }
                    ?: diagnostics?.brakePressurePsi?.let { "%.1f".format(Locale.US, it) }
                    ?: "--",
                Modifier.weight(1f),
            )
        }
        Text(
            text = canDecodedDiagnosticsText(diagnostics, frame),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CanFrameFreshnessGrid(health)
        Text(
            text = listOfNotNull(
                health?.usbDeviceName?.let { "USB: $it" },
                health?.canBitrate?.let { "Bitrate: $it" },
                "Connected: ${connected}; waiting for frames: ${waitingForFrames}",
                health?.rawCanSample?.let { "Raw: $it" },
                diagnostics?.let { controlsRawText(it) },
                rawSamplesText(health),
                health?.degradedReason?.let { "Reason: $it" },
                if (health?.signUnverified == true) "Signed channels need first-drive sign validation" else null,
            ).joinToString("\n").ifBlank { "Connect RH-02 PRO/CANable and start the AiM CAN USB source." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LearningPlanPanel(state: SessionUiState) {
    WorkSurface(
        title = "Learning Plan",
        meta = if (state.selectedGoalFocuses.isEmpty()) "not staged" else "${state.selectedGoalFocuses.size} focus areas",
        tag = "learning-plan-panel",
    ) {
        Text(
            text = if (state.selectedGoalFocuses.isEmpty()) {
                "No verified Learning Plan staged. The next setup can still use manual session goals."
            } else {
                "Next session will prioritize ${state.selectedGoalFocuses.joinToString { it.name.lowercase(Locale.US) }}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncPanel(state: SessionUiState) {
    WorkSurface(
        title = "Sync",
        meta = if (state.savedSession == null) "waiting" else "ready",
        tag = "sync-panel",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("ADB", if (state.savedSession == null) "idle" else "pull ready", Modifier.weight(1f))
            MetricTile("Hotspot", "local only", Modifier.weight(1f))
            MetricTile("Integrity", if (state.savedSession == null) "--" else "checked", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SavedSessionPanel(state: SessionUiState) {
    state.savedSession?.let { session ->
        WorkSurface(
            title = "Saved Session",
            meta = "schema v${session.schemaVersion}",
            tag = "saved-session",
        ) {
            Text(
                "${session.totalFrameCount} frames (${session.embeddedFrameCount} preview), ${session.summary.decisionCount} decisions, ended ${session.endedReason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOfNotNull(
                session.artifactPath?.let { path -> "Session JSON: $path" },
                session.framesPath?.let { path -> "Frames: $path" },
                session.decisionsPath?.let { path -> "Decisions: $path" },
                session.audioEventsPath?.let { path -> "Audio events: $path" },
                session.canDumpPath?.let { path -> "CAN dump: $path" },
            ).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } ?: WorkSurface(
        title = "Saved Session",
        meta = "none",
        tag = "saved-session-empty",
    ) {
        Text(
            "No session has been recorded on this device yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DecisionHistory(decisions: List<CoachingDecision>) {
    WorkSurface(
        title = "Cue History",
        meta = "${decisions.size}",
        tag = "coach-panel",
    ) {
        if (decisions.isEmpty()) {
            Text(
                text = "No coaching decisions yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                decisions.forEach { decision ->
                    DecisionRow(decision)
                }
            }
        }
    }
}

@Composable
private fun DecisionRow(decision: CoachingDecision) {
    val priorityColor = trackCueColor(decision.priority)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KoruDimens.ChipRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            StatusPill("P${decision.priority}", priorityColor, trackPriorityIcon(decision.priority))
        },
        headlineContent = {
            Text(
                text = decision.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = "${decision.path.name} ${decision.action?.name ?: "cue"} ${decision.cornerPhase.name} ${decision.latencyMs ?: "--"}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun WorkSurface(
    title: String,
    meta: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .koruCardBorder(RoundedCornerShape(KoruDimens.CardRadius)),
        shape = RoundedCornerShape(KoruDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(KoruDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(title, meta)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        StatusPill(meta, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                val optionTag = "option-${
                    label.lowercase(Locale.US).replace(" ", "-")
                }-${text.lowercase(Locale.US).replace(" ", "-")}"
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    shape = RoundedCornerShape(KoruDimens.ChipRadius),
                    label = {
                        Text(
                            text = text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp)
                        .testTag(optionTag),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(KoruDimens.PillRadius),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
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

@Composable
private fun CanFrameFreshnessGrid(health: TelemetrySourceHealth?) {
    val observedIds = (
        CAN_TEST_FRAME_IDS +
            health?.canFrameRatesHz.orEmpty().keys +
            health?.canFrameAgesMs.orEmpty().keys +
            health?.rawCanSamplesById.orEmpty().keys
        ).distinct().sorted()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        observedIds.chunked(4).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowIds.forEach { frameId ->
                    val stale = health?.canFrameStale?.get(frameId)
                    val age = health?.canFrameAgesMs?.get(frameId)
                    val rate = health?.canFrameRatesHz?.get(frameId)
                    val color = when (stale) {
                        false -> KoruPalette.SignalReady
                        true -> KoruPalette.Error
                        null -> KoruPalette.SignalNeutral
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .koruCardBorder(RoundedCornerShape(KoruDimens.ChipRadius)),
                        shape = RoundedCornerShape(KoruDimens.ChipRadius),
                        color = color.copy(alpha = 0.1f),
                        contentColor = color,
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(frameId, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = age?.let { "${it}ms" } ?: "--",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = rate?.let { "%.0fHz".format(Locale.US, it) } ?: "--",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun canDecodedDiagnosticsText(diagnostics: CanVehicleDiagnostics?, frame: TelemetryFrame?): String {
    if (diagnostics == null) return "Decoded channels: waiting for mapped AiM CAN frames."
    val wheelAverage = listOfNotNull(
        diagnostics.wheelSpeedFrontLeftMph,
        diagnostics.wheelSpeedFrontRightMph,
        diagnostics.wheelSpeedRearLeftMph,
        diagnostics.wheelSpeedRearRightMph,
    ).takeIf { it.isNotEmpty() }?.average()
    return listOf(
        "Speed GPS/ECU/Wheel: ${fmt1(diagnostics.gpsSpeedMph)} / ${fmt1(diagnostics.ecuSpeedMph)} / ${fmt1(wheelAverage)} mph",
        "Temps water/oil filter/engine/outside: ${fmt1(diagnostics.waterTempC)} / ${fmt1(diagnostics.oilFilterTempC)} / ${fmt1(diagnostics.engineOilTempC)} / ${fmt1(diagnostics.outsideTempC)} C",
        "Pressures water/oil/brake: ${fmt1(diagnostics.waterPressurePsi)} / ${fmt1(diagnostics.oilPressurePsi)} / ${fmt1(diagnostics.brakePressurePsi)} psi",
        "Brake calibrated/offset: ${fmt1(diagnostics.brakePressureCalibratedPsi)} / raw ${diagnostics.brakePressureZeroOffsetRaw?.toString() ?: "--"} (${fmt1(diagnostics.brakePressureZeroOffsetPsi)} psi)",
        "Controls pedal/brake switch/DSC: ${fmt1(diagnostics.pedalPositionPercent)}% / ${diagnostics.brakeSwitchApplied?.toString() ?: "--"} / ${diagnostics.dscRegActive?.toString() ?: "--"}",
        "Angles/rates steer/yaw/pitch/roll: ${fmt1(diagnostics.steeringAngleDeg)} deg / ${fmt1(diagnostics.yawRateDegPerSec)} / ${fmt1(diagnostics.pitchRateDegPerSec)} / ${fmt1(diagnostics.rollRateDegPerSec)} deg/s",
        "G lat/long/vertical: ${fmt1(frame?.gLat ?: diagnostics.lateralG)} / ${fmt1(frame?.gLong ?: diagnostics.inlineG)} / ${fmt1(diagnostics.verticalG)}",
        "Wheel FL/FR/RL/RR: ${fmt1(diagnostics.wheelSpeedFrontLeftMph)} / ${fmt1(diagnostics.wheelSpeedFrontRightMph)} / ${fmt1(diagnostics.wheelSpeedRearLeftMph)} / ${fmt1(diagnostics.wheelSpeedRearRightMph)} mph",
        "Fuel/Battery/Gear raw: ${fmt1(diagnostics.fuelLevelGal)} gal / ${fmt1(diagnostics.batteryVoltage)} V / ${diagnostics.gearRaw?.toString() ?: "--"}",
    ).joinToString("\n")
}

private fun controlsRawText(diagnostics: CanVehicleDiagnostics): String? {
    return listOfNotNull(
        diagnostics.brakePressureRaw?.let { "brakeRaw=$it" },
        diagnostics.brakePressurePsi?.let { "brakePsi=${fmt1(it)}" },
        diagnostics.brakePressureZeroOffsetRaw?.let { "brakeZeroRaw=$it" },
        diagnostics.brakePressureCalibratedPsi?.let { "brakeCalPsi=${fmt1(it)}" },
        diagnostics.pedalPositionRaw?.let { "pedalRaw=$it" },
        diagnostics.brakeSwitchRaw?.let { "brakeSwitchRaw=$it" },
    ).joinToString(prefix = "0x422 raw: ", separator = ", ").takeIf { it != "0x422 raw: " }
}

private fun rawSamplesText(health: TelemetrySourceHealth?): String? {
    val samples = health?.rawCanSamplesById.orEmpty()
    if (samples.isEmpty()) return null
    val ids = samples.keys.sorted()
    val selected = (CAN_TEST_FRAME_IDS.filter { it in samples } + ids.filter { it !in CAN_TEST_FRAME_IDS }).take(12)
    return buildString {
        append("Observed CAN IDs: ")
        append(ids.joinToString(", "))
        append('\n')
        append("Raw by ID: ")
        append(selected.joinToString(" | ") { id -> "$id=${samples[id]}" })
    }
}

private fun fmt1(value: Double?): String {
    return value?.let { "%.1f".format(Locale.US, it) } ?: "--"
}

private val CAN_TEST_FRAME_IDS = listOf("0x420", "0x421", "0x422", "0x423", "0x424", "0x450", "0x451", "0x452")
