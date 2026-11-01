package com.example.glpred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's type scale and spacing, in one place.
 *
 * The screens had grown a dozen ad-hoc sizes (10, 11, 12, 13, 14, 15 sp) and gaps of 2, 4, 6, 8, 12
 * and 16 dp. Each choice was defensible on its own and the sum of them reads as carelessness, so the
 * scale is now four sizes and three gaps - few enough to hold in your head, which is what makes it
 * enforceable rather than aspirational.
 */
object Type {
    val section = 15.sp
    val body = 13.sp
    val label = 11.sp
}

object Space {
    val tight = 4.dp
    val normal = 8.dp
    val loose = 16.dp
}

/**
 * Colours that carry a meaning rather than a brand.
 *
 * Red is deliberately absent: it means destruction or a failed action, and states like "waiting for
 * an import" are neither. The model keeps one green everywhere it appears, so the chart, the verdict
 * strip and a status badge cannot disagree about which colour is the model.
 */
object StateColors {
    val model = Color(0xFF2E7D32)
    val waiting = Color(0xFFB26A00)
}

/**
 * A state label: a coloured dot and its text, on one line.
 *
 * A dot rather than a filled chip because these sit inside cards that already have a surface, and a
 * second filled shape competes with the values it is meant to annotate. The dot carries the state;
 * the words explain it.
 */
@Composable
fun StatusDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.tight)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            fontSize = Type.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A label on the left, its value on the right, on the same baseline.
 *
 * Used instead of dot-separated chains ("128 mg/dL starting · 6 carb unit/s · basis 0 u"), which
 * have to be read left to right as a sentence. Two columns can be scanned.
 */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = Type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = Type.body,
            fontWeight = FontWeight.Medium
        )
    }
}
