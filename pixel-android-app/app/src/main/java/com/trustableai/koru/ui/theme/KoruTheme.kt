package com.trustableai.koru.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun KoruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = koruDarkColorScheme(),
        typography = KoruTypography,
        shapes = KoruShapes,
        content = content,
    )
}

@Composable
fun Modifier.koruCardBorder(shape: RoundedCornerShape = RoundedCornerShape(KoruDimens.CardRadius)): Modifier {
    return clip(shape)
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = shape,
        )
}
