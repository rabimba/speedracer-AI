package com.trustableai.koru.model

enum class CoachAction {
    THRESHOLD,
    TRAIL_BRAKE,
    BRAKE,
    WAIT,
    TURN_IN,
    COMMIT,
    ROTATE,
    APEX,
    THROTTLE,
    PUSH,
    FULL_THROTTLE,
    STABILIZE,
    MAINTAIN,
    COAST,
    HESITATION,
    OVERSTEER_RECOVERY,
    EARLY_THROTTLE,
    LIFT_MID_CORNER,
    SPIKE_BRAKE,
    COGNITIVE_OVERLOAD,
    HUSTLE,
}

enum class CornerPhase {
    STRAIGHT,
    BRAKE_ZONE,
    TURN_IN,
    MID_CORNER,
    APEX,
    EXIT,
    ACCELERATION,
}

enum class CoachingPath {
    HOT,
    COLD,
    FEEDFORWARD,
    EDGE,
}

enum class RuntimeBackend {
    BROWSER,
    AICORE,
    LITERTLM,
    DETERMINISTIC,
}

enum class LiveBackendState {
    IDLE,
    STARTING,
    READY,
    DEGRADED,
    UNAVAILABLE,
    ERROR,
}

enum class SkillLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
}

enum class SessionMode {
    TELEMETRY,
    DEVICE_TEST,
    CAMERA_DIRECT,
}

enum class TelemetrySourceKind {
    SYNTHETIC,
    PHONE_IMU_GPS,
    RACEBOX_BLE,
    OBD_BLUETOOTH,
}

data class TelemetryFrame(
    val timeSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speedMph: Double,
    val rpm: Int? = null,
    val throttle: Double,
    val brake: Double,
    val steering: Double? = null,
    val gLat: Double,
    val gLong: Double,
    val gear: Int? = null,
    val distanceMeters: Double? = null,
    val sourceMode: SessionMode = SessionMode.TELEMETRY,
    val telemetrySource: TelemetrySourceKind? = null,
    val vision: VisionFeatureSnapshot? = null,
)

data class Corner(
    val id: Int,
    val name: String,
    val entryDist: Double,
    val apexDist: Double,
    val exitDist: Double,
    val lat: Double,
    val lon: Double,
    val advice: String,
    val entryLat: Double? = null,
    val entryLon: Double? = null,
    val targetSpeed: Double? = null,
)

data class Track(
    val name: String,
    val lengthMeters: Double,
    val centerLat: Double,
    val centerLon: Double,
    val corners: List<Corner>,
)

data class DriverState(
    val skillLevel: SkillLevel,
    val cognitiveLoad: Double,
    val inputSmoothness: Double,
    val coastingRatio: Double,
)

data class VisionFeatureSnapshot(
    val timestampMs: Long,
    val averageLuma: Double,
    val motionEnergy: Double,
    val lateralBalance: Double,
    val verticalBalance: Double,
    val centerContrast: Double,
    val framesPerSecond: Double,
)

data class EdgeReasoningWindow(
    val triggerId: String,
    val actionHint: CoachAction,
    val priority: Int,
    val suggestedText: String,
    val phase: CornerPhase,
    val skillLevel: SkillLevel,
    val cornerName: String?,
    val features: Map<String, Double>,
)

data class ReasonerDecision(
    val speak: Boolean,
    val action: CoachAction,
    val priority: Int,
    val phraseId: String,
    val confidence: Double,
    val text: String,
)

data class CoachingDecision(
    val path: CoachingPath,
    val action: CoachAction? = null,
    val text: String,
    val priority: Int,
    val cornerPhase: CornerPhase,
    val timestampMs: Long,
    val backend: RuntimeBackend,
    val latencyMs: Long? = null,
    val confidence: Double? = null,
    val phraseId: String? = null,
)

data class LiveBackendStatus(
    val backend: RuntimeBackend,
    val state: LiveBackendState,
    val detail: String,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val model: String? = null,
    val usesOnDeviceModel: Boolean,
    val supportedPaths: List<CoachingPath>,
)

data class RecordedSessionSummary(
    val sessionId: String,
    val mode: SessionMode,
    val trackName: String,
    val coachId: String,
    val frameCount: Int,
    val decisionCount: Int,
    val durationSeconds: Double,
)

data class RecordedSessionArtifact(
    val id: String,
    val mode: SessionMode,
    val trackName: String,
    val coachId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val summary: RecordedSessionSummary,
    val frames: List<TelemetryFrame>,
    val decisions: List<CoachingDecision>,
)

data class ModelInstallStatus(
    val version: String,
    val isPresent: Boolean,
    val checksumVerified: Boolean,
    val downloadAllowedOnCurrentNetwork: Boolean,
    val filePath: String,
    val fileName: String,
    val supportsNativeAndroid: Boolean,
    val issue: String? = null,
)

fun CoachingPath.bridgeValue(): String = name.lowercase()

fun RuntimeBackend.bridgeValue(): String = name.lowercase()

fun LiveBackendState.bridgeValue(): String = name.lowercase()

fun SessionMode.bridgeValue(): String = name.lowercase()

fun TelemetrySourceKind.bridgeValue(): String = name.lowercase()
