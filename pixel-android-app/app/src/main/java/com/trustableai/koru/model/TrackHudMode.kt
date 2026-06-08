package com.trustableai.koru.model

enum class TrackHudMode {
    SIGNAL_ONLY,
    SIGNAL_PLUS_TEXT,
}

fun TrackHudMode.bridgeValue(): String = when (this) {
    TrackHudMode.SIGNAL_ONLY -> "signal_only"
    TrackHudMode.SIGNAL_PLUS_TEXT -> "signal_plus_text"
}

fun trackHudModeFromBridge(value: String?): TrackHudMode = when (value) {
    "signal_plus_text" -> TrackHudMode.SIGNAL_PLUS_TEXT
    else -> TrackHudMode.SIGNAL_ONLY
}
