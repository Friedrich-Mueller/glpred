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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.glpred.prediction.ModelVsHuman
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

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
    // Two colours and dots on one series. A dashed or striped line was unreadable at this stroke
    // width - it looked like a rendering artefact, not a second series.
    val modelColor = Color(0xFF2E7D32)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val allValues = yourPoints.map { it.youUnitsPerCarb } +
        modelCurve.filterNotNull() +
        yourPoints.mapNotNull { it.modelUnitsPerCarb }
    val step = niceStep(allValues.maxOrNull() ?: 1.0)
    val maxY = (ceil(((allValues.maxOrNull() ?: 1.0) * 1.15) / step) * step).coerceAtLeast(step)

    Column(modifier = modifier) {
        Row {
            // Tick labels stay real text: a canvas at this size cannot draw a legible one.
            Column(
                modifier = Modifier
                    .height(118.dp)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(formatTick(maxY), fontSize = 9.sp, color = labelColor)
                Text(formatTick(maxY / 2), fontSize = 9.sp, color = labelColor)
                Text(formatTick(0.0), fontSize = 9.sp, color = labelColor)
            }
            Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
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
            drawLine(gridColor, Offset(0f, y(maxY / 2)), Offset(w, y(maxY / 2)), strokeWidth = 1f)

            // The model: continuous across the whole day.
            val modelPoints = modelCurve.withIndex().mapNotNull { (hour, value) ->
                value?.let { Offset(x(hour * 60), y(it)) }
            }
            for (i in 1 until modelPoints.size) {
                drawLine(
                    color = modelColor,
                    start = modelPoints[i - 1],
                    end = modelPoints[i],
                    strokeWidth = 3f
                )
            }

            // Yours: only where you ate, connected so a trend is visible.
            val yourOffsets = yourPoints.map { Offset(x(it.hourOfDay * 60), y(it.youUnitsPerCarb)) }
            for (i in 1 until yourOffsets.size) {
                drawLine(youColor, yourOffsets[i - 1], yourOffsets[i], strokeWidth = 3f)
            }
            yourOffsets.forEach { drawCircle(youColor, radius = 6f, center = it) }

            // Axes last, so they sit on top of everything and cannot be mistaken for a data line.
            drawLine(axisColor, Offset(0f, 0f), Offset(0f, h), strokeWidth = 2f)
            drawLine(axisColor, Offset(0f, h), Offset(w, h), strokeWidth = 2f)
        }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 18.dp)
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
            LegendEntry(youColor, "you (average per hour, ${yourPoints.sumOf { it.meals }} meals)")
            LegendEntry(modelColor, "model (every hour)")
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

/** A round axis step, so the printed ticks are numbers a human would have chosen. */
private fun niceStep(maxValue: Double): Double = when {
    maxValue <= 0.5 -> 0.1
    maxValue <= 1.2 -> 0.25
    maxValue <= 3.0 -> 0.5
    maxValue <= 6.0 -> 1.0
    else -> 2.0
}

private fun formatTick(value: Double): String = when {
    abs(value - round(value)) < 1e-9 -> String.format(Locale.US, "%.0f", value)
    abs(value * 10 - round(value * 10)) < 1e-9 -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.2f", value)
}
