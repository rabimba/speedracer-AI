package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionGoalSource
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import org.json.JSONArray
import org.json.JSONObject

data class LiveSessionConfig(
    val coachId: String,
    val audioEnabled: Boolean,
    val trackName: String,
    val sessionMode: SessionMode,
    val telemetrySource: TelemetrySourceKind,
    val sessionGoals: List<SessionGoal>,
    val sourceUrl: String?,
) {
    companion object {
        fun fromJson(json: String): LiveSessionConfig {
            val root = JSONObject(json)
            return LiveSessionConfig(
                coachId = root.optString("coachId", "superaj"),
                audioEnabled = root.optBoolean("audioEnabled", true),
                trackName = root.optString("trackName", TrackCatalog.defaultTrack.name),
                sessionMode = when (root.optString("sessionMode", "telemetry")) {
                    "device_test" -> SessionMode.DEVICE_TEST
                    "camera_direct" -> SessionMode.CAMERA_DIRECT
                    else -> SessionMode.TELEMETRY
                },
                telemetrySource = when (root.optString("telemetrySource", "synthetic")) {
                    "phone_imu_gps" -> TelemetrySourceKind.PHONE_IMU_GPS
                    "racebox_ble" -> TelemetrySourceKind.RACEBOX_BLE
                    "obd_bluetooth" -> TelemetrySourceKind.OBD_BLUETOOTH
                    else -> TelemetrySourceKind.SYNTHETIC
                },
                sessionGoals = parseSessionGoals(root.optJSONArray("sessionGoals")),
                sourceUrl = root.optString("sourceUrl").ifBlank { null },
            )
        }

        private fun parseSessionGoals(values: JSONArray?): List<SessionGoal> {
            if (values == null) return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val goal = values.optJSONObject(index) ?: continue
                    add(
                        SessionGoal(
                            id = goal.optString("id", "goal-$index"),
                            focus = when (goal.optString("focus", "custom")) {
                                "braking" -> SessionGoalFocus.BRAKING
                                "throttle" -> SessionGoalFocus.THROTTLE
                                "vision" -> SessionGoalFocus.VISION
                                "lines" -> SessionGoalFocus.LINES
                                "smoothness" -> SessionGoalFocus.SMOOTHNESS
                                else -> SessionGoalFocus.CUSTOM
                            },
                            description = goal.optString("description", "Custom session focus"),
                            source = when (goal.optString("source", "pre_race_chat")) {
                                "auto_generated" -> SessionGoalSource.AUTO_GENERATED
                                "coach_assigned" -> SessionGoalSource.COACH_ASSIGNED
                                else -> SessionGoalSource.PRE_RACE_CHAT
                            },
                            prioritizedActions = parseActions(goal.optJSONArray("prioritizedActions")),
                        ),
                    )
                }
            }.take(3)
        }

        private fun parseActions(values: JSONArray?): List<CoachAction> {
            if (values == null) return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val actionName = values.optString(index).trim()
                    if (actionName.isEmpty()) continue
                    runCatching { CoachAction.valueOf(actionName) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }
    }
}
