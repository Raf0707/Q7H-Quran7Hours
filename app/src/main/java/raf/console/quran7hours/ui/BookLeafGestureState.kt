package raf.console.quran7hours.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Stable snapshot of Pager movement, including the settled-page switch. */
private data class LeafPagerSample(
    val current: Int,
    val offset: Float,
    val settled: Int,
    val scrolling: Boolean
)

/**
 * Draws the CURL for the *whole* gesture, including its last frames.
 *
 * Pager's settledPage can switch and reset the current offset before the final
 * curl frame is displayed. We keep the original bitmap indices and complete
 * the remaining part (or rewind on a cancelled gesture) over real frames.
 * The extra animation only begins when Pager has finished, never fights the
 * finger while the user is still dragging slowly.
 */
@Composable
internal fun rememberCompletedBookLeafFrame(
    pager: PagerState,
    totalPages: Int
): BookLeafTurnPlan.Frame? {
    var visualFrame by remember(pager) { mutableStateOf<BookLeafTurnPlan.Frame?>(null) }

    LaunchedEffect(pager, totalPages) {
        var origin = pager.settledPage
        var lastDragged: BookLeafTurnPlan.Frame? = null
        var completion: Job? = null

        snapshotFlow {
            LeafPagerSample(
                current = pager.currentPage,
                offset = pager.currentPageOffsetFraction,
                settled = pager.settledPage,
                scrolling = pager.isScrollInProgress
            )
        }.collect { sample ->
            if (sample.scrolling) {
                completion?.cancel()
                completion = null
                // Keep the same outgoing page even after currentPage switches
                // at the halfway mark (or settledPage is updated at the end).
                if (lastDragged == null) origin = sample.settled
                val candidate = BookLeafTurnPlan.calculate(
                    currentIndex = sample.current,
                    offsetFraction = sample.offset,
                    settledIndex = origin,
                    totalPages = totalPages
                )
                if (candidate.visible) {
                    lastDragged = candidate
                    visualFrame = candidate
                } else if (candidate.progress <= 0.004f) {
                    // Returned to the start, or a new swipe began while the
                    // previous finishing coroutine was cancelled.
                    visualFrame = null
                    lastDragged = null
                    origin = sample.settled
                }
            } else {
                val finalDragged = lastDragged
                lastDragged = null
                origin = sample.settled
                if (finalDragged != null && finalDragged.visible) {
                    completion?.cancel()
                    val committed = sample.settled == finalDragged.destinationIndex
                    val destination = if (committed) 1f else 0f
                    completion = launch {
                        val anim = Animatable(finalDragged.progress.coerceIn(0f, 1f))
                        // Deliberate finishing even if Pager settles at ~80-95%.
                        // The minimum time prevents the last piece from blinking away.
                        val remaining = kotlin.math.abs(destination - anim.value)
                        val duration = (260f + 240f * remaining).toInt()
                        anim.animateTo(
                            destination,
                            animationSpec = tween(durationMillis = duration, easing = PageTurnEasing.settle)
                        ) {
                            visualFrame = finalDragged.copy(progress = value, visible = true)
                        }
                        visualFrame = finalDragged.copy(progress = destination, visible = true)
                        withFrameNanos { } // Show fully flat/fully edge-on at least one frame.
                        withFrameNanos { }
                        if (!pager.isScrollInProgress) visualFrame = null
                    }
                }
            }
        }
    }
    return visualFrame
}
