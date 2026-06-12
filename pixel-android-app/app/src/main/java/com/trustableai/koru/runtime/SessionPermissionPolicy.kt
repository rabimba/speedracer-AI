package com.trustableai.koru.runtime

import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind

object SessionPermissionPolicy {
    fun requiresFineLocation(config: LiveSessionConfig): Boolean {
        return config.sessionMode == SessionMode.DEVICE_TEST ||
            (config.sessionMode == SessionMode.TELEMETRY &&
                config.telemetrySource in setOf(
                    TelemetrySourceKind.PHONE_IMU_GPS,
                    TelemetrySourceKind.RACEBOX_OBD_FUSION,
                ))
    }

    fun requiresBluetooth(config: LiveSessionConfig): Boolean {
        return config.sessionMode == SessionMode.TELEMETRY &&
            config.telemetrySource in setOf(
                TelemetrySourceKind.RACEBOX_BLE,
                TelemetrySourceKind.OBD_BLUETOOTH,
                TelemetrySourceKind.RACEBOX_OBD_FUSION,
            )
    }

    fun requiresLocationForegroundType(config: LiveSessionConfig): Boolean {
        return requiresFineLocation(config)
    }
}
