package raf.console.quran7hours.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kinematic approximation of a flexible paper sheet, separate from Android and Compose.
 * Integrating a varying tangent angle gives a genuinely curved mesh rather than a
 * perspective rotation of one rigid quad. X always advances monotonically: the
 * Arabic page never folds through itself or flips its glyphs backwards.
 */
internal object BookTurnMath {
    const val COLUMNS = 72
    const val ROWS = 16
    private const val EDGE_ON_DEGREES = 89.1f

    data class Profile(val x: FloatArray, val depth: FloatArray, val tangent: FloatArray)

    fun smooth(t: Float): Float {
        val p = safe(t)
        return p * p * (3f - 2f * p)
    }

    fun safe(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    /** Old face rises; new face returns along a traveling curl from the opposite edge. */
    fun profile(progress: Float, unfolding: Boolean = false, columns: Int = COLUMNS): Profile {
        require(columns > 0)
        val p = safe(progress)
        val angle = Math.toRadians((EDGE_ON_DEGREES * smooth(p)).toDouble())
        val waveEnvelope = sin(PI * p).coerceAtLeast(0.0)
        val amplitude = Math.toRadians(42.0) * waveEnvelope * waveEnvelope
        // Change the traveling crest direction between lifting and laying the page down.
        // While laying the new themed page down (p: 1 -> 0), the wave starts
        // at the book spine (u=0) and travels to the free edge (u=1).
        val crest = if (unfolding) 0.10 + (1.0 - p) * 0.78 else 0.07 + p * 0.80
        val x = FloatArray(columns + 1)
        val depth = FloatArray(columns + 1)
        val tangent = FloatArray(columns + 1)
        var previous = 0.0
        for (i in 0..columns) {
            val u = i.toDouble() / columns
            val gate = sin(PI * u).coerceAtLeast(0.0)
            val travelingWave = sin(2.0 * PI * (u - crest))
            // Rise with a single rounded crest; no angular crease that cuts Quran text.
            val theta = (angle + amplitude * gate * travelingWave)
                .coerceIn(Math.toRadians(-36.0), Math.toRadians(89.4))
            tangent[i] = theta.toFloat()
            if (i > 0) {
                // Trapezoid integration of the rotated paper tangent.
                val step = 1.0 / columns
                x[i] = (x[i-1] + step * 0.5 * (cos(previous) + cos(theta))).toFloat()
                depth[i] = (depth[i-1] + step * 0.5 * (sin(previous) + sin(theta))).toFloat()
            }
            previous = theta
        }
        return Profile(x, depth, tangent)
    }
}
