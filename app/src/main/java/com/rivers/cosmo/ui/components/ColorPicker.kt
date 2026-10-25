package com.rivers.cosmo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.rivers.cosmo.settings.ColorMath
import com.rivers.cosmo.settings.Hsv
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A hue/saturation color wheel: the angle picks the hue and the distance from the center picks
 * the saturation. Brightness is set separately, and the wheel darkens to match it.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                fun pick(position: Offset) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f
                    val d = position - center
                    val angle = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
                    currentOnChange((angle + 360f) % 360f, (hypot(d.x, d.y) / radius).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        pick(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val radius = size.minDimension / 2f
        // Sweep gradients start at 3 o'clock and go clockwise, matching the angle used in pick().
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
            ),
            radius
        )
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
        drawCircle(Color.Black.copy(alpha = 1f - brightness), radius)

        val angle = Math.toRadians(hue.toDouble())
        val marker = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (saturation * radius)
        drawCircle(Color.Black, 12.dp.toPx(), marker, style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, 12.dp.toPx(), marker, style = Stroke(2.5.dp.toPx()))
    }
}

/** A dialog with a color wheel, a brightness slider and a hex code field. */
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (argb: Int) -> Unit
) {
    var hsv by remember { mutableStateOf(ColorMath.toHsv(initialColor)) }
    var hexText by remember { mutableStateOf(ColorMath.toHex(initialColor)) }
    val color = ColorMath.fromHsv(hsv)
    fun update(newHsv: Hsv) {
        hsv = newHsv
        hexText = ColorMath.toHex(ColorMath.fromHsv(newHsv))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ColorWheel(
                    hue = hsv.hue,
                    saturation = hsv.saturation,
                    brightness = hsv.value,
                    onChange = { hue, saturation -> update(hsv.copy(hue = hue, saturation = saturation)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Brightness", style = MaterialTheme.typography.labelLarge)
                Slider(value = hsv.value, onValueChange = { update(hsv.copy(value = it)) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSwatch(color, Modifier.size(48.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            ColorMath.parseHex(text)?.let { hsv = ColorMath.toHsv(it) }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        isError = ColorMath.parseHex(hexText) == null,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        )
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A filled circle showing a color, outlined so it stays visible on a matching background. */
@Composable
fun ColorSwatch(argb: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color(argb), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}
