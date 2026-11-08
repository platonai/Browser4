package ai.platon.browser4.behavior.ghostcursor

import ai.platon.browser4.behavior.humanization.MoveWaypoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Plans a ghost-cursor-style pointer trajectory: a bent Bézier with a Fitts-law step count,
 * a long-range overshoot-and-correct, and variable speed along the path.
 *
 * The model is the one `Xetera/ghost-cursor` uses for `moveTo`, restated as a pure function so
 * the policy can be tested without a browser:
 *
 * 1. **Step count** — [BASE_STEPS] plus [STEPS_PER_DIFFICULTY_BIT] per bit of
 *    [GhostCursorMath.fitts] difficulty. A far, small target therefore gets more intermediate
 *    events than a near, large one, and a one-pixel hop cannot produce a flood of events
 *    (the count is capped at one event per [MIN_PIXELS_PER_STEP] pixels).
 * 2. **Overshoot** — a travel longer than [OVERSHOOT_THRESHOLD_PX] is split in two: the pointer
 *    first heads for a point up to [GhostCursorMath.OVERSHOOT_RADIUS] px *past* the target,
 *    then converges on the target along a tight ([GhostCursorMath.OVERSHOOT_CONVERGE_SPREAD]
 *    px spread) curve. A human reaching across a screen does exactly this; a straight
 *    single-segment move does not.
 * 3. **Speed** — the total duration scales with distance ([SPEED_PX_PER_MS]) and each sample is
 *    timed by arc length through a smooth-step warp, so the pointer eases out, crosses fast and
 *    settles.
 *
 * The last waypoint is always the exact target, which the click path relies on: a trajectory
 * that ends anywhere else presses outside the element box.
 *
 * @param random the source of all randomness; inject a seeded instance for reproducible tests.
 */
internal class GhostCursorTrajectory(
    private val random: Random = Random.Default,
) {
    /**
     * Plan the trajectory from ([fromX], [fromY]) to ([toX], [toY]).
     *
     * @param targetWidth the target element's width, in CSS pixels; `0` when unknown, in which
     *   case [DEFAULT_TARGET_WIDTH_PX] is used for the Fitts difficulty.
     * @return the points to dispatch, in order, each with the wait that precedes it. The final
     *   point is exactly the requested target.
     */
    fun plan(fromX: Double, fromY: Double, toX: Double, toY: Double, targetWidth: Double = 0.0): List<MoveWaypoint> {
        val from = Vector2(fromX, fromY)
        val target = Vector2(toX, toY)
        val distance = GhostCursorMath.magnitude(GhostCursorMath.sub(target, from))

        if (distance == 0.0) {
            // Already resting on the target: a single confirmation move keeps the contract that
            // the plan is non-empty and ends exactly on the target.
            return listOf(MoveWaypoint(target.x, target.y, 0))
        }

        val width = if (targetWidth > 0.0) targetWidth else DEFAULT_TARGET_WIDTH_PX
        val totalSteps = stepCount(distance, width)

        return if (distance > OVERSHOOT_THRESHOLD_PX) {
            planWithOvershoot(from, target, distance, totalSteps)
        } else {
            planSegment(from, target, totalSteps, spread(distance))
        }
    }

    /**
     * The Fitts-scaled step count, capped so a short travel cannot emit more than one event per
     * [MIN_PIXELS_PER_STEP] pixels.
     */
    fun stepCount(distance: Double, targetWidth: Double): Int {
        val difficulty = GhostCursorMath.fitts(distance, targetWidth)
        val fittsSteps = (BASE_STEPS + difficulty * STEPS_PER_DIFFICULTY_BIT).roundToInt()
        val distanceCap = max(MIN_STEPS, ceil(distance / MIN_PIXELS_PER_STEP).toInt())
        return fittsSteps.coerceIn(MIN_STEPS, distanceCap.coerceAtMost(MAX_STEPS))
    }

    /**
     * Two-segment plan: out to an overshoot point past the target, then back onto it with a
     * tight spread.
     *
     * The steps split in proportion to each segment's length so the convergence leg (usually
     * short) is not given the same event budget as the main travel.
     */
    private fun planWithOvershoot(
        from: Vector2,
        target: Vector2,
        distance: Double,
        totalSteps: Int,
    ): List<MoveWaypoint> {
        val overshootPoint = clampToViewport(
            GhostCursorMath.overshoot(target, GhostCursorMath.OVERSHOOT_RADIUS, random)
        )
        val convergeLength = GhostCursorMath.magnitude(GhostCursorMath.sub(overshootPoint, target))
        val totalLength = distance + convergeLength
        val convergeSteps = if (totalLength == 0.0) {
            MIN_STEPS
        } else {
            (totalSteps * convergeLength / totalLength).roundToInt().coerceIn(MIN_STEPS, MAX_STEPS)
        }
        val approachSteps = (totalSteps - convergeSteps).coerceAtLeast(MIN_STEPS)

        val approach = planSegment(from, overshootPoint, approachSteps, spread(distance))
        val converge = planSegment(
            overshootPoint,
            target,
            convergeSteps,
            GhostCursorMath.OVERSHOOT_CONVERGE_SPREAD,
        )

        // The approach ends on the overshoot point, which the convergence leg starts from:
        // dropping the duplicate keeps exactly one mousemove per position.
        return approach + converge.drop(1)
    }

    /** One Bézier leg from [from] to [to], sampled and timed. */
    private fun planSegment(from: Vector2, to: Vector2, steps: Int, spread: Double): List<MoveWaypoint> {
        val anchors = GhostCursorMath.generateBezierAnchors(from, to, spread, random)
        val length = GhostCursorMath.magnitude(GhostCursorMath.sub(to, from))
        val duration = (length / SPEED_PX_PER_MS).coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)
        // `steps` counts intermediate events; the curve needs one more sample than that.
        val timed = GhostCursorMath.timedBezierCurve(anchors, steps + 1, duration)

        val waypoints = ArrayList<MoveWaypoint>(timed.size)
        for (index in 1 until timed.size) {
            val point = timed[index]
            val delay = (point.timeMillis - timed[index - 1].timeMillis)
                .roundToLong()
                .coerceAtLeast(0)
            val isLast = index == timed.size - 1
            // The final point is the caller's target and must not be nudged; intermediate
            // points are clamped the way ghost-cursor clamps its path into non-negative space.
            val x = if (isLast) point.x else max(0.0, point.x)
            val y = if (isLast) point.y else max(0.0, point.y)
            waypoints += MoveWaypoint(x, y, delay)
        }
        return waypoints
    }

    /**
     * The control-point spread for a leg: ghost-cursor draws it uniformly from
     * [GhostCursorMath.SPREAD_MIN] to 200 px, and so does this, scaled down for a short travel
     * so a 20 px hop cannot bow out by 200 px.
     */
    private fun spread(distance: Double): Double {
        val upper = (distance / 2.0).coerceIn(GhostCursorMath.SPREAD_MIN, GhostCursorMath.SPREAD_MAX)
        return GhostCursorMath.randomRange(GhostCursorMath.SPREAD_MIN, upper, random)
    }

    private fun clampToViewport(point: Vector2): Vector2 = Vector2(max(0.0, point.x), max(0.0, point.y))

    companion object {
        /** A travel longer than this, in CSS pixels, is split by an overshoot. */
        internal const val OVERSHOOT_THRESHOLD_PX: Double = 500.0

        /** Step count before the Fitts difficulty is added. */
        internal const val BASE_STEPS: Int = 25

        /** Extra intermediate events per bit of Fitts difficulty. */
        internal const val STEPS_PER_DIFFICULTY_BIT: Double = 5.0

        /** Never dispatch more than one event per this many pixels of travel. */
        internal const val MIN_PIXELS_PER_STEP: Double = 2.0

        /** Floor for a segment's intermediate events. */
        internal const val MIN_STEPS: Int = 3

        /** Ceiling for a segment's intermediate events. */
        internal const val MAX_STEPS: Int = 80

        /** Travel speed used to derive the trajectory duration. */
        internal const val SPEED_PX_PER_MS: Double = 1.8

        /** Shortest leg duration, so a short hop still has a visible rhythm. */
        internal const val MIN_DURATION_MILLIS: Double = 80.0

        /** Longest leg duration, so a viewport-crossing travel does not crawl. */
        internal const val MAX_DURATION_MILLIS: Double = 700.0

        /** Width assumed when the caller does not know the target's size. */
        internal const val DEFAULT_TARGET_WIDTH_PX: Double = 100.0
    }
}
