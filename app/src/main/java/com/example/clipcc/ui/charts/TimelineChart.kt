package com.example.clipcc.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class TimelineSeries(val label: String, val color: Color, val values: List<Float>)
data class TimelineBand(val color: Color, val startFrac: Float, val endFrac: Float)

/** Score-over-time chart on [0,1] y, frame-index x. [bands] are shaded segments (fractions of width). */
@Composable
fun TimelineChart(
    series: List<TimelineSeries>, threshold: Float, bands: List<TimelineBand>,
    modifier: Modifier = Modifier,
) {
    val desc = "Timeline of ${series.size} labels over ${series.firstOrNull()?.values?.size ?: 0} frames"
    Canvas(modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = desc }) {
        fun x(i: Int, n: Int) = if (n <= 1) 0f else size.width * i / (n - 1)
        fun y(v: Float) = size.height * (1f - v.coerceIn(0f, 1f))

        bands.forEach { b ->
            drawRect(b.color.copy(alpha = 0.15f),
                topLeft = Offset(size.width * b.startFrac, 0f),
                size = androidx.compose.ui.geometry.Size(size.width * (b.endFrac - b.startFrac), size.height))
        }
        val dashed = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        drawLine(Color.Gray, Offset(0f, y(threshold)), Offset(size.width, y(threshold)),
            strokeWidth = 2f, pathEffect = dashed)
        series.forEach { s ->
            val path = Path()
            s.values.forEachIndexed { i, v ->
                val px = x(i, s.values.size); val py = y(v)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, s.color, style = Stroke(width = 4f))
        }
    }
}
