package com.example.glpred.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.glpred.prediction.ModelVsHuman
import java.util.Locale

/**
 * Two curves over the 24-hour clock: your dose per carb unit, and the model's.
 *
 * The model's line is continuous because it is the model's own answer for a reference meal at every
 * hour - it can be evaluated anywhere. Yours only exists where you actually ate, so it is drawn as a
 * line through those hours with a dot at each one: the dots are evidence, the segments between them
 * are only a connection.
 */
@Composable
fun RatioChart(
    yourPoints: List<ModelVsHuman.RatioPoint>,
    modelCurve: List<Double?>,
    modifier: Modifier = Modifier
) {
    val youColor = MaterialTheme.colorScheme.primary
    // The model line is drawn dark and dashed: tertiary against a card surface is too pale to see,
    // and a solid look-alike would read as a second measurement rather than a different kind of claim.
    val modelColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val allValues = yourPoints.map { it.youUnitsPerCarb } +
        modelCurve.filterNotNull() +
        yourPoints.mapNotNull { it.modelUnitsPerCarb }
    val maxY = ((allValues.maxOrNull() ?: 1.0) * 1.2).coerceAtLeast(0.5)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        ) {
            val w = size.width
            val h = size.height

            fun x(minuteOfDay: Int) = w * minuteOfDay / (24f * 60f)
            fun y(unitsPerCarb: Double) = h - (unitsPerCarb / maxY).toFloat() * h

            // Grid every six hours, so the eye can place a curve in the day.
            for (hour in 0..24 step 6) {
                val gx = x(hour * 60)
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 1f)

            // The model: continuous across the whole day.
            val modelPoints = modelCurve.withIndex().mapNotNull { (hour, value) ->
                value?.let { Offset(x(hour * 60), y(it)) }
            }
            for (i in 1 until modelPoints.size) {
                drawLine(
                    color = modelColor,
                    start = modelPoints[i - 1],
                    end = modelPoints[i],
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                )
            }

            // Yours: only where you ate, connected so a trend is visible.
            val yourOffsets = yourPoints.map { Offset(x(it.hourOfDay * 60), y(it.youUnitsPerCarb)) }
            for (i in 1 until yourOffsets.size) {
                drawLine(youColor, yourOffsets[i - 1], yourOffsets[i], strokeWidth = 3f)
            }
            yourOffsets.forEach { drawCircle(youColor, radius = 6f, center = it) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        ) {
            Text("00:00", fontSize = 10.sp, color = labelColor)
            Box(modifier = Modifier.weight(1f))
            Text("12:00", fontSize = 10.sp, color = labelColor)
            Box(modifier = Modifier.weight(1f))
            Text("24:00", fontSize = 10.sp, color = labelColor)
        }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LegendEntry(youColor, "yours ${formatCurveValue(yourPoints)} (dots = meals)")
            LegendEntry(modelColor, "model ${formatModelRange(modelCurve)}")
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

private fun formatCurveValue(points: List<ModelVsHuman.RatioPoint>): String {
    if (points.isEmpty()) return "-"
    val min = points.minOf { it.youUnitsPerCarb }
    val max = points.maxOf { it.youUnitsPerCarb }
    return String.format(Locale.US, "%.2f-%.2f u/CU", min, max)
}

private fun formatModelRange(curve: List<Double?>): String {
    val values = curve.filterNotNull()
    if (values.isEmpty()) return "no model"
    return String.format(Locale.US, "%.2f-%.2f u/CU", values.min(), values.max())
}
