package com.trustableai.koru.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.trustableai.koru.model.LiveBackendState

object KoruPalette {
    val BackgroundDeep = Color(0xFF050505)
    val BackgroundMid = Color(0xFF0D0F0D)
    val BackgroundGlow = Color(0xFF18150F)

    val SurfaceBase = Color(0xFF10110F)
    val SurfaceContainerLow = Color(0xFF151713)
    val SurfaceContainer = Color(0xFF1A1D18)
    val SurfaceContainerHigh = Color(0xFF20241D)
    val SurfaceContainerHighest = Color(0xFF292D25)

    val Primary = Color(0xFFB7F06A)
    val OnPrimary = Color(0xFF131908)
    val PrimaryContainer = Color(0xFF263616)
    val OnPrimaryContainer = Color(0xFFDAF5A8)

    val Secondary = Color(0xFF7AD7D1)
    val OnSecondary = Color(0xFF071A18)
    val SecondaryContainer = Color(0xFF163331)
    val OnSecondaryContainer = Color(0xFFBDEFEA)

    val Tertiary = Color(0xFFF0A65A)
    val OnTertiary = Color(0xFF221508)
    val TertiaryContainer = Color(0xFF352311)
    val OnTertiaryContainer = Color(0xFFFFD8A8)

    val OnBackground = Color(0xFFF3F4EF)
    val OnSurface = Color(0xFFF3F4EF)
    val OnSurfaceVariant = Color(0xFFB9BDB3)

    val Error = Color(0xFFE85D6C)
    val OnError = Color(0xFF2A0A0E)
    val ErrorContainer = Color(0xFF3A1418)
    val OnErrorContainer = Color(0xFFF5D0D4)

    val Outline = Color(0xFF353A30)
    val OutlineSubtle = Color(0x14FFFFFF)

    val SignalUrgent = Color(0xFFE85D6C)
    val SignalAdvisory = Color(0xFFE6A23C)
    val SignalReady = Color(0xFF7BC96F)
    val SignalNeutral = Color(0xFF6B7280)
    val SignalInfo = Color(0xFF7EB8D4)

    val HoldStopSurface = Color(0xFF2A1216)
    val HoldStopContent = Color(0xFFF5D0D4)

    val Divider = Color(0xFF1E2634)
    val Scrim = Color(0xCC040508)
}

fun koruDarkColorScheme() = darkColorScheme(
    primary = KoruPalette.Primary,
    onPrimary = KoruPalette.OnPrimary,
    primaryContainer = KoruPalette.PrimaryContainer,
    onPrimaryContainer = KoruPalette.OnPrimaryContainer,
    secondary = KoruPalette.Secondary,
    onSecondary = KoruPalette.OnSecondary,
    secondaryContainer = KoruPalette.SecondaryContainer,
    onSecondaryContainer = KoruPalette.OnSecondaryContainer,
    tertiary = KoruPalette.Tertiary,
    onTertiary = KoruPalette.OnTertiary,
    tertiaryContainer = KoruPalette.TertiaryContainer,
    onTertiaryContainer = KoruPalette.OnTertiaryContainer,
    background = KoruPalette.BackgroundDeep,
    onBackground = KoruPalette.OnBackground,
    surface = KoruPalette.SurfaceBase,
    onSurface = KoruPalette.OnSurface,
    surfaceVariant = KoruPalette.SurfaceContainer,
    onSurfaceVariant = KoruPalette.OnSurfaceVariant,
    surfaceContainerLowest = KoruPalette.BackgroundDeep,
    surfaceContainerLow = KoruPalette.SurfaceContainerLow,
    surfaceContainer = KoruPalette.SurfaceContainer,
    surfaceContainerHigh = KoruPalette.SurfaceContainerHigh,
    surfaceContainerHighest = KoruPalette.SurfaceContainerHighest,
    error = KoruPalette.Error,
    onError = KoruPalette.OnError,
    errorContainer = KoruPalette.ErrorContainer,
    onErrorContainer = KoruPalette.OnErrorContainer,
    outline = KoruPalette.Outline,
    outlineVariant = KoruPalette.OutlineSubtle,
)

fun trackCueColor(priority: Int?): Color = when (priority) {
    0, 1 -> KoruPalette.SignalUrgent
    2, 3 -> KoruPalette.SignalAdvisory
    else -> KoruPalette.SignalReady
}

fun trackStatusColor(state: LiveBackendState): Color = when (state) {
    LiveBackendState.READY -> KoruPalette.SignalReady
    LiveBackendState.DEGRADED -> KoruPalette.SignalAdvisory
    LiveBackendState.ERROR, LiveBackendState.UNAVAILABLE -> KoruPalette.Error
    LiveBackendState.STARTING -> KoruPalette.SignalInfo
    LiveBackendState.IDLE -> KoruPalette.SignalNeutral
}
