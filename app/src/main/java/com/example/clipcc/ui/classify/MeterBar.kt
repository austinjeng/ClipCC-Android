package com.example.clipcc.ui.classify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Decorative score meter. The adjacent % text carries the value for accessibility. */
@Composable
fun MeterBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 6.dp) {
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))
            .background(color.copy(alpha = 0.15f)).clearAndSetSemantics {}
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height)
                .clip(RoundedCornerShape(height / 2)).background(color)
        )
    }
}
