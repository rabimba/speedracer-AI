package com.trustableai.koru.runtime

import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import org.json.JSONObject

data class LiveSessionConfig(
    val coachId: String,
    val audioEnabled: Boolean,
    val trackName: String,
    val sessionMode: SessionMode,
    val telemetrySource: TelemetrySourceKind,
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
                sourceUrl = root.optString("sourceUrl").ifBlank { null },
            )
        }
    }
}
