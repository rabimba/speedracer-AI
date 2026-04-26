package com.trustableai.koru.runtime

import com.trustableai.koru.model.SessionMode
import org.json.JSONObject

data class LiveSessionConfig(
    val coachId: String,
    val audioEnabled: Boolean,
    val trackName: String,
    val sessionMode: SessionMode,
    val sourceUrl: String?,
) {
    companion object {
        fun fromJson(json: String): LiveSessionConfig {
            val root = JSONObject(json)
            return LiveSessionConfig(
                coachId = root.optString("coachId", "superaj"),
                audioEnabled = root.optBoolean("audioEnabled", true),
                trackName = root.optString("trackName", TrackCatalog.thunderhillEast.name),
                sessionMode = when (root.optString("sessionMode", "telemetry")) {
                    "camera_direct" -> SessionMode.CAMERA_DIRECT
                    else -> SessionMode.TELEMETRY
                },
                sourceUrl = root.optString("sourceUrl").ifBlank { null },
            )
        }
    }
}
