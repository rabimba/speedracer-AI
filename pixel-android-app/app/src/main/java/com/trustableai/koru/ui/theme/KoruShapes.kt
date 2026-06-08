package com.trustableai.koru.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val KoruShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

object KoruDimens {
    val ScreenPadding = 24.dp
    val SectionSpacing = 16.dp
    val CardPadding = 20.dp
    val ChipRadius = 8.dp
    val CardRadius = 8.dp
    val PillRadius = 999.dp
    val MinTouchTarget = 56.dp
}
