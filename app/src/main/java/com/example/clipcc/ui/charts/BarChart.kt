package com.example.clipcc.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Grouped bars on a single declared scale. [zeroAtCenter] draws a symmetric axis (for signed cosine);
 *  otherwise bars rise from the bottom on [0, max]. [thresholdLine] draws an optional guide (e.g. 0.5). */
@Composable
fun BarChart(
    bars: List<Bar>, max: Float, barColor: Color,
    modifier: Modifier = Modifier, zeroAtCenter: Boolean = false, thresholdLine: Float? = null,
) {
    val desc = bars.joinToString("; ") { "${it.label} ${"%.3f".format(it.value)}" }
    Canvas(
        modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Bar chart: $desc" }
    ) {
        val n = bars.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = slot * 0.6f
        val zeroY = if (zeroAtCenter) size.height / 2f else size.height
        fun y(v: Float) = zeroY - (v / max) * (if (zeroAtCenter) size.height / 2f else size.height)

        thresholdLine?.let { t ->
            val ty = y(t)
            drawLine(Color.Gray, androidx.compose.ui.geometry.Offset(0f, ty),
                androidx.compose.ui.geometry.Offset(size.width, ty), strokeWidth = 2f)
        }
        bars.forEachIndexed { i, b ->
            val cx = i * slot + slot / 2f
            val top = minOf(zeroY, y(b.value))
            val h = kotlin.math.abs(zeroY - y(b.value))
            drawRect(barColor,
                topLeft = androidx.compose.ui.geometry.Offset(cx - barW / 2f, top),
                size = androidx.compose.ui.geometry.Size(barW, h))
        }
        if (zeroAtCenter) drawLine(Color.DarkGray,
            androidx.compose.ui.geometry.Offset(0f, zeroY),
            androidx.compose.ui.geometry.Offset(size.width, zeroY), strokeWidth = 1f)
    }
}
