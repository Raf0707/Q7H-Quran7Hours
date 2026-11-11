package raf.console.quran7hours.ui

import kotlin.math.abs

/**
 * Stable, direction-aware state for a book-leaf placed ABOVE HorizontalPager.
 *
 * The settled page stays the outgoing sheet until the swipe is finished.
 * The logical position is continuous even when currentPage switches at 50%,
 * so the curl never jumps back to zero halfway through the gesture.
 */
internal object BookLeafTurnPlan {
    data class Frame(
        val sourceIndex: Int,
        val destinationIndex: Int,
        val progress: Float,
        val forward: Boolean,
        val visible: Boolean
    )

    fun calculate(
        currentIndex: Int,
        offsetFraction: Float,
        settledIndex: Int,
        totalPages: Int
    ): Frame {
        require(totalPages > 0)
        val from = settledIndex.coerceIn(0, totalPages - 1)
        val offset = if (offsetFraction.isFinite()) offsetFraction.coerceIn(-0.5f, 0.5f) else 0f
        val delta = (currentIndex.toDouble() + offset.toDouble() - from.toDouble())
        val forward = delta >= 0.0
        val to = from + if (forward) 1 else -1
        val p = abs(delta).toFloat().coerceIn(0f, 1f)
        val active = to in 0 until totalPages && p > 0.004f
        return Frame(from, if (active) to else from, p, forward, active)
    }
}
