package fr.relance.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import fr.relance.app.core.Physio
import fr.relance.app.core.RunnerState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private data class Gait(val leg: Float, val knee: Float, val kneeBase: Float, val arm: Float, val elbow: Float, val lean: Float, val bob: Float)

private fun gaitFor(s: RunnerState) = when (s) {
    RunnerState.STOPPED -> Gait(0f, 0f, 2f, 0f, 8f, 0f, 0f)
    RunnerState.WALK -> Gait(23f, 30f, 4f, 20f, 12f, 2f, 1.5f)
    RunnerState.JOG -> Gait(32f, 60f, 10f, 32f, 75f, 6f, 4f)
    RunnerState.FAST -> Gait(44f, 90f, 14f, 46f, 90f, 12f, 6f)
}

/**
 * Le coureur animé. Sa cadence suit la vitesse en continu ; son attitude illustre le test de la parole :
 * il chante ou discute en marchant, parle en trottinant, transpire quand il court vite.
 */
@Composable
fun Runner(speedKmh: Double, state: RunnerState, animate: Boolean, modifier: Modifier = Modifier) {
    val speed by animateFloatAsState(speedKmh.toFloat(), tween(1000), label = "vitesse")
    val g = gaitFor(state)
    val leg by animateFloatAsState(g.leg, tween(600), label = "jambe")
    val knee by animateFloatAsState(g.knee, tween(600), label = "genou")
    val kneeBase by animateFloatAsState(g.kneeBase, tween(600), label = "genouBase")
    val arm by animateFloatAsState(g.arm, tween(600), label = "bras")
    val elbow by animateFloatAsState(g.elbow, tween(600), label = "coude")
    val lean by animateFloatAsState(g.lean, tween(600), label = "buste")
    val bob by animateFloatAsState(g.bob, tween(600), label = "rebond")

    var phase by remember { mutableFloatStateOf(0.12f) }
    var ground by remember { mutableFloatStateOf(0f) }
    var clock by remember { mutableFloatStateOf(0f) }
    val liveSpeed by rememberUpdatedState(speed)

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    clock += dt
                    if (liveSpeed > 0.5f) {
                        phase = (phase + dt / Physio.strideSeconds(liveSpeed.toDouble()).toFloat()) % 1f
                        ground = (ground + dt * liveSpeed * 18f) % 3200f
                    }
                }
                last = now
            }
        }
    }

    Canvas(modifier.semantics { contentDescription = "Coureur animé. ${state.label}" }) {
        val u = size.height / 136f
        val hipX = size.width * 0.45f
        val moving = state != RunnerState.STOPPED
        val lift = if (moving) -bob * abs(sin(2 * PI.toFloat() * phase)) else -1.5f * (0.5f + 0.5f * sin(clock * 2.1f))
        val hip = Offset(hipX, (80f + lift) * u)

        // Sol qui défile.
        val period = 32f * u
        var x = -((ground * u) % period)
        while (x < size.width) {
            drawLine(C.NightLine, Offset(x, 130f * u), Offset(x + 14f * u, 130f * u), 3f * u, StrokeCap.Round)
            x += period
        }

        fun rad(deg: Float) = deg * PI.toFloat() / 180f
        fun dir(deg: Float) = Offset(-sin(rad(deg)), cos(rad(deg)))

        fun drawLeg(t: Float, color: Color) {
            val theta = -leg * cos(2 * PI.toFloat() * t)
            val bend = kneeBase + knee * max(0f, sin(2 * PI.toFloat() * (t - 0.5f)))
            val kneePt = hip + dir(theta) * (22f * u)
            val ankle = kneePt + dir(theta + bend) * (22f * u)
            val a = rad(theta + bend)
            val toe = ankle + Offset(cos(a), sin(a)) * (8f * u)
            drawLine(color, hip, kneePt, 8f * u, StrokeCap.Round)
            drawLine(color, kneePt, ankle, 8f * u, StrokeCap.Round)
            drawLine(color, ankle, toe, 8f * u, StrokeCap.Round)
        }

        val up = Offset(sin(rad(lean)), -cos(rad(lean)))
        val shoulder = hip + up * (34f * u)
        val head = hip + up * (51f * u)

        fun drawArm(t: Float, color: Color) {
            val phi = -arm * cos(2 * PI.toFloat() * t) + lean
            val elbowPt = shoulder + dir(phi) * (18f * u)
            val hand = elbowPt + dir(phi - elbow) * (16f * u)
            drawLine(color, shoulder, elbowPt, 7f * u, StrokeCap.Round)
            drawLine(color, elbowPt, hand, 7f * u, StrokeCap.Round)
        }

        val back = Color(0xFF7C8A8E)
        drawArm(phase, back)
        drawLeg(phase + 0.5f, back)
        drawLine(C.Mint, hip, shoulder, 11f * u, StrokeCap.Round)
        drawCircle(Color.White, 11f * u, head)
        drawLeg(phase, Color.White)
        drawArm(phase + 0.5f, Color.White)

        when (state) {
            RunnerState.WALK -> if (clock % 8f < 4f) notes(head, u, clock) else bubble(head, u, clock)
            RunnerState.JOG -> bubble(head, u, clock)
            RunnerState.FAST -> sweat(head, u, clock)
            RunnerState.STOPPED -> Unit
        }
    }
}

private fun fade(p: Float) = (if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f).coerceIn(0f, 1f)

private fun DrawScope.notes(head: Offset, u: Float, clock: Float) {
    val bases = listOf(Offset(28f, 4f), Offset(44f, -6f), Offset(60f, 8f))
    bases.forEachIndexed { i, b ->
        val p = (clock / 2.4f + i / 3f) % 1f
        val c = head + Offset((b.x + 14f * p) * u, (b.y - 22f * p) * u)
        val color = C.Mint.copy(alpha = fade(p))
        drawOval(color, Offset(c.x - 6.4f * u, c.y - 2.6f * u), Size(6.8f * u, 5.2f * u))
        drawLine(color, c, c + Offset(0f, -13f * u), 2f * u, StrokeCap.Round)
        drawLine(color, c + Offset(0f, -13f * u), c + Offset(7f * u, -15f * u), 2f * u, StrokeCap.Round)
    }
}

private fun DrawScope.bubble(head: Offset, u: Float, clock: Float) {
    val origin = head + Offset(24f * u, -24f * u)
    drawRoundRect(Color.White, origin, Size(58f * u, 30f * u), CornerRadius(12f * u))
    val tail = Path().apply {
        moveTo(origin.x + 8f * u, origin.y + 28f * u)
        lineTo(origin.x + 1f * u, origin.y + 37f * u)
        lineTo(origin.x + 16f * u, origin.y + 29f * u)
        close()
    }
    drawPath(tail, Color.White)
    for (i in 0 until 3) {
        val pulse = 0.25f + 0.75f * (0.5f + 0.5f * sin((clock / 1.2f - i / 3f) * 2 * PI.toFloat()))
        drawCircle(C.Night.copy(alpha = pulse), 3.5f * u, origin + Offset((17f + 12f * i) * u, 15f * u))
    }
}

private fun DrawScope.sweat(head: Offset, u: Float, clock: Float) {
    val bases = listOf(Offset(-14f, -14f), Offset(-22f, 0f), Offset(-12f, 14f))
    bases.forEachIndexed { i, b ->
        val p = (clock / 0.9f + i / 3f) % 1f
        val c = head + Offset((b.x - 18f * p) * u, (b.y + 28f * p) * u)
        val color = C.Sweat.copy(alpha = fade(p))
        val drop = Path().apply {
            moveTo(c.x, c.y)
            cubicTo(c.x + 3f * u, c.y + 4f * u, c.x + 4f * u, c.y + 6f * u, c.x + 4f * u, c.y + 8f * u)
            cubicTo(c.x + 4f * u, c.y + 13.3f * u, c.x - 4f * u, c.y + 13.3f * u, c.x - 4f * u, c.y + 8f * u)
            cubicTo(c.x - 4f * u, c.y + 6f * u, c.x - 3f * u, c.y + 4f * u, c.x, c.y)
            close()
        }
        drawPath(drop, color)
        drawPath(drop, color, style = Stroke(0.5f * u))
    }
}
