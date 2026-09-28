package com.ultron.ui.orb

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The five states the reference design (and the earlier HTML prototype) defines.
 * Drive this enum from your voice pipeline: wake-word -> LISTENING, LLM call ->
 * THINKING, TTS playback -> SPEAKING, task failure/roast -> ALERT, else IDLE.
 */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ALERT }

private val CyanBlob = Color(0xFF4FD8FF)
private val PurpleBlob = Color(0xFF8A5CFF)
private val BlueBlob = Color(0xFF3B6BFF)
private val RingColor = Color(0xFF8ECBFF)
private val AlertColor = Color(0xFFFF6A3D)

/**
 * Amplitude in [0f, 1f]: feed this from your VAD / audio-output RMS to drive
 * real audio-reactive scaling in LISTENING and SPEAKING. Defaults to a gentle
 * synthetic pulse when you don't have live audio yet.
 */
@Composable
fun AssistantOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    amplitude: Float = 0f,
) {
    // One shared infinite clock; each visual driven off it at a different rate
    // so IDLE/LISTENING/THINKING/SPEAKING all reuse the same transition object.
    val transition = rememberInfiniteTransition(label = "orb")

    val churnAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    OrbState.THINKING -> 1400
                    OrbState.SPEAKING -> 2200
                    else -> 9000
                },
                easing = LinearEasing,
            )
        ),
        label = "churn",
    )

    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == OrbState.SPEAKING) 260 else 1100,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val flareAlpha by animateFloatAsState(
        targetValue = if (state == OrbState.ALERT) 1f else 0f,
        animationSpec = tween(220),
        label = "flare",
    )

    val ringColor by animateColorAsState(
        targetValue = if (state == OrbState.ALERT) AlertColor else RingColor,
        label = "ringColor",
    )

    val scale = when (state) {
        OrbState.IDLE -> 1f
        OrbState.LISTENING -> 1f + 0.06f * pulse + 0.10f * amplitude
        OrbState.THINKING -> 1f
        OrbState.SPEAKING -> 1f + 0.12f * pulse + 0.15f * amplitude
        OrbState.ALERT -> 1f
    }

    Box(modifier = modifier.size(220.dp), contentAlignment = Alignment.Center) {
        // Glowing orbital ring
        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 2.dp.toPx()
            drawCircle(
                color = ringColor.copy(alpha = 0.35f),
                radius = size.minDimension / 2f - strokeWidth,
                style = Stroke(width = strokeWidth),
            )
            // orbiting dot marks the ring, same idea as the CSS ::before dot
            val r = size.minDimension / 2f - strokeWidth
            val rad = Math.toRadians(churnAngle.toDouble())
            val dot = Offset(
                x = center.x + r * cos(rad).toFloat(),
                y = center.y + r * sin(rad).toFloat(),
            )
            drawCircle(color = ringColor, radius = 4.dp.toPx(), center = dot)
        }

        // Liquid core
        Box(
            modifier = Modifier
                .size(170.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = if (state == OrbState.THINKING) churnAngle else 0f
                }
                .clip(CircleShape)
                .background(Color(0xFF04060C)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBlob(CyanBlob, angle = churnAngle, phase = 0f, blendMode = BlendMode.Plus)
                drawBlob(PurpleBlob, angle = churnAngle, phase = 120f, blendMode = BlendMode.Plus)
                drawBlob(BlueBlob, angle = churnAngle, phase = 240f, blendMode = BlendMode.Plus)
            }
            // Alert / roast flare
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(AlertColor.copy(alpha = 0.9f), Color.Transparent),
                        ),
                    )
                    .graphicsLayer { alpha = flareAlpha },
            )
        }
    }
}

/** One soft, drifting radial-gradient blob standing in for a 3D fluid displacement. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlob(
    color: Color,
    angle: Float,
    phase: Float,
    blendMode: BlendMode,
) {
    val rad = Math.toRadians((angle + phase).toDouble())
    val driftRadius = size.minDimension * 0.12f
    val center = Offset(
        x = size.width / 2f + driftRadius * cos(rad).toFloat(),
        y = size.height / 2f + driftRadius * sin(rad).toFloat(),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.85f), Color.Transparent),
            center = center,
            radius = size.minDimension * 0.55f,
        ),
        radius = size.minDimension * 0.55f,
        center = center,
        blendMode = blendMode,
    )
}

/** Drop-in demo screen wiring five buttons to the five states — same layout as the HTML mock. */
@Composable
fun OrbDemoScreen() {
    var state by remember { mutableStateOf(OrbState.IDLE) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
            AssistantOrb(state = state)
            Text(state.name.lowercase().replaceFirstChar { it.uppercase() }, color = Color(0xFF7FA0C8))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrbState.entries.forEach { s ->
                    Button(onClick = {
                        state = s
                        // ALERT auto-returns to IDLE, same as the web prototype
                    }) { Text(s.name) }
                }
            }
        }
    }

    // Auto-snap ALERT back to IDLE after a short flare, like the HTML version's setTimeout.
    LaunchedEffect(state) {
        if (state == OrbState.ALERT) {
            kotlinx.coroutines.delay(1400)
            if (state == OrbState.ALERT) state = OrbState.IDLE
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OrbPreview() {
    MaterialTheme {
        OrbDemoScreen()
    }
}
