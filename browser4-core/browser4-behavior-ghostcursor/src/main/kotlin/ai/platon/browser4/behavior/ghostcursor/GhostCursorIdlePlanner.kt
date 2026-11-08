package ai.platon.browser4.behavior.ghostcursor

import ai.platon.browser4.behavior.humanization.PointerPosition
import kotlin.random.Random

/**
 * Plans the wandering a pointer does while nothing is being asked of it.
 *
 * `Xetera/ghost-cursor` calls this `performRandomMoves`: with no interaction in flight, the
 * pointer drifts to a random point of the page every so often, so a session that idles for a
 * minute does not present a cursor frozen at one coordinate (which is itself a signal). The
 * driver stops the wander as soon as a real interaction starts.
 *
 * This class is the pure decision half — *where* and *how long to wait* — so it is testable
 * without a browser. The driver owns the coroutine that applies it.
 *
 * @param random the source of all randomness; inject a seeded instance for reproducible tests.
 */
internal class GhostCursorIdlePlanner(
    private val random: Random = Random.Default,
) {
    /**
     * A uniformly random viewport point.
     *
     * @param viewportWidth the viewport width, in CSS pixels.
     * @param viewportHeight the viewport height, in CSS pixels.
     * @return a point inside the viewport; the origin when the viewport size is unknown.
     */
    fun nextPoint(viewportWidth: Double, viewportHeight: Double): PointerPosition {
        if (viewportWidth <= 0.0 || viewportHeight <= 0.0) {
            return PointerPosition(0.0, 0.0)
        }
        return PointerPosition(
            x = random.nextDouble() * viewportWidth,
            y = random.nextDouble() * viewportHeight,
        )
    }

    /**
     * The wait after a wander move, uniformly drawn from
     * [MIN_MOVE_DELAY_MILLIS]..[MAX_MOVE_DELAY_MILLIS].
     *
     * ghost-cursor's `moveDelay` is `random · 2000`; the range form keeps the same scale while
     * letting the driver configure the floor.
     */
    fun nextDelayMillis(): Long = random.nextLong(MIN_MOVE_DELAY_MILLIS, MAX_MOVE_DELAY_MILLIS + 1)

    companion object {
        /** Shortest pause between two idle wander moves, in milliseconds. */
        internal const val MIN_MOVE_DELAY_MILLIS: Long = 0L

        /** Longest pause between two idle wander moves, in milliseconds. */
        internal const val MAX_MOVE_DELAY_MILLIS: Long = 2_000L
    }
}
