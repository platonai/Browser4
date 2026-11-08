package ai.platon.browser4.behavior.ghostcursor

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.humanization.ClickPointRequest
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.DragPlan
import ai.platon.browser4.behavior.humanization.DragRequest
import ai.platon.browser4.behavior.humanization.IdleMovePlan
import ai.platon.browser4.behavior.humanization.IdleMoveRequest
import ai.platon.browser4.behavior.humanization.InteractionHumanizer
import ai.platon.browser4.behavior.humanization.PointerMovePlan
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import ai.platon.browser4.behavior.humanization.PointerPosition
import kotlin.random.Random

/**
 * An [InteractionHumanizer] that ports the `Xetera/ghost-cursor` interaction model.
 *
 * It differs from the conservative community default in the three places ghost-cursor is
 * known for:
 *
 * | Decision | This policy | Community default |
 * |---|---|---|
 * | pointer trajectory | bent cubic Bézier with random normal-line anchors, Fitts-law step count, an overshoot-and-correct leg past 500 px, and smooth-step variable speed ([GhostCursorTrajectory]) | ~20 px per straight-ish segment at a constant 8–16 ms |
 * | click point | uniform anywhere inside the element box (`paddingPercentage` shrinks the box, `100` = always centre) | centre nudged by at most ±2 px |
 * | idle session | the pointer wanders to a random viewport point every 0–2 s ([GhostCursorIdlePlanner]) | the pointer stays wherever it was |
 *
 * Two deliberate departures from the JavaScript original, both required by this repository's
 * direct-CDP review gates (see AGENTS.md):
 *
 * - **button hold and multi-click gap keep the configured bucket pacing** instead of ghost's
 *   `0 ms` default: a press released in the same millisecond is a bot fingerprint, and a
 *   double click with no gap is not something a hand produces.
 * - **the click point keeps a 1 px inset** from the element edge: landing exactly on a
 *   fractional edge can round outside the box and lose the CSS `:hover` state the move exists
 *   to establish.
 *
 * Selection: `priority = 0` ties with the community default, and the registry breaks a tie by
 * id in ascending order, so `default` still wins. This provider changes nothing until it is
 * pinned with `browser.behavior.humanization.provider=ghostcursor`.
 *
 * Registered through `META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer`.
 *
 * @param random the source of all randomness. [Random.Default] is thread-safe and is what
 *   production uses; a seeded instance makes the policy reproducible and must then only be
 *   shared by single-threaded tests.
 * @param paddingPercentage shrinks the click box before the point is drawn: `0` allows the
 *   whole element, `100` always returns its centre.
 */
class GhostCursorInteractionHumanizer(
    private val random: Random = Random.Default,
    private val paddingPercentage: Double = 0.0,
) : InteractionHumanizer {
    override val id: String = ID
    override val edition: String = BrowserBehaviorEdition.CUSTOM
    override val priority: Int = 0

    private val trajectory = GhostCursorTrajectory(random)
    private val idlePlanner = GhostCursorIdlePlanner(random)

    /**
     * Return the full trajectory, not a step count: the driver dispatches these points in
     * order, which is what makes the overshoot and the variable speed observable at all.
     */
    override fun pointerMove(request: PointerMoveRequest, delays: DelaySource): PointerMovePlan {
        val waypoints = trajectory.plan(
            fromX = request.fromX,
            fromY = request.fromY,
            toX = request.toX,
            toY = request.toY,
            targetWidth = request.targetWidth,
        )
        return PointerMovePlan(
            steps = waypoints.size,
            delayMillis = 0,
            waypoints = waypoints,
        )
    }

    /** A uniformly random point inside the element box, as ghost-cursor's `getRandomBoxPoint`. */
    override fun clickPoint(request: ClickPointRequest): PointerPosition {
        val paddingX = request.width * paddingPercentage / 200.0
        val paddingY = request.height * paddingPercentage / 200.0
        val innerWidth = request.width - paddingX * 2 - EDGE_INSET_PX * 2
        val innerHeight = request.height - paddingY * 2 - EDGE_INSET_PX * 2

        // An unknown, fully padded or tiny box cannot hold a random point: the exact centre is
        // the only answer that keeps `:hover` and cannot click an overlay.
        if (innerWidth <= 0.0 || innerHeight <= 0.0) {
            return PointerPosition(request.x, request.y)
        }

        val left = request.x - request.width / 2 + paddingX + EDGE_INSET_PX
        val top = request.y - request.height / 2 + paddingY + EDGE_INSET_PX
        return PointerPosition(
            x = left + random.nextDouble() * innerWidth,
            y = top + random.nextDouble() * innerHeight,
        )
    }

    /** A real press is held; the base library's `click` bucket is the pacing policy. */
    override fun pressHoldMillis(delays: DelaySource): Long = delays.delayMillis(CLICK_BUCKET)

    /** A real double click has a gap between rounds; reuse the `click` bucket. */
    override fun multiClickGapMillis(delays: DelaySource): Long = delays.delayMillis(CLICK_BUCKET)

    /** Keep the base library's `press` bucket in charge of key pacing. */
    override fun keyPressDelayMillis(delays: DelaySource): Long = delays.delayMillis(PRESS_BUCKET)

    /**
     * The base library's `type` bucket, doubled for a supplementary character.
     *
     * A surrogate pair is inserted as two UTF-16 units, and a 0 ms delay between
     * `Input.insertText` calls drops `input` events (see AGENTS.md).
     */
    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long {
        val delay = delays.delayMillis(TYPE_BUCKET)
        return if (codePoint.length > 1) delay * 2 else delay
    }

    /**
     * Jitter the press/release points and randomize the inter-event delays of the drag.
     *
     * ghost-cursor has no drag model, so this keeps the community default's semantics: a
     * positioned (`top`/`bottom`) drop pins its target to the live rect, because a jittered
     * edge point makes insert-before/insert-after a coin flip.
     */
    override fun dragPlan(request: DragRequest, delays: DelaySource): DragPlan {
        val targetX = if (request.positioned) request.targetX else request.targetX + randomOffset()
        val targetY = if (request.positioned) request.targetY else request.targetY + randomOffset()
        val delayCount = if (request.positioned) POSITIONED_DRAG_DELAYS else CENTER_DRAG_DELAYS
        return DragPlan(
            sourceX = request.sourceX + randomOffset(),
            sourceY = request.sourceY + randomOffset(),
            targetX = targetX,
            targetY = targetY,
            delays = List(delayCount) { random.nextLong(MIN_DRAG_DELAY_MILLIS, MAX_DRAG_DELAY_MILLIS + 1) },
        )
    }

    /**
     * The idle wander ghost-cursor calls `performRandomMoves`: a random viewport point plus a
     * random 0–2 s rest. The driver starts it only when
     * `browser.behavior.humanization.idle.enabled` is set.
     */
    override fun idleMove(request: IdleMoveRequest): IdleMovePlan? {
        val point = idlePlanner.nextPoint(request.viewportWidth, request.viewportHeight)
        return IdleMovePlan(point.x, point.y, idlePlanner.nextDelayMillis())
    }

    /** Uniform random offset in `[-DRAG_POINT_JITTER_PX, DRAG_POINT_JITTER_PX]`. */
    private fun randomOffset(): Double =
        random.nextDouble(-DRAG_POINT_JITTER_PX, DRAG_POINT_JITTER_PX)

    companion object {
        /** Provider id used by configuration and diagnostics. */
        const val ID: String = "ghostcursor"

        /** Keep a random click point this far inside the element box. */
        internal const val EDGE_INSET_PX: Double = 1.0

        /** Jitter applied to the drag press/release points. */
        internal const val DRAG_POINT_JITTER_PX: Double = 2.0

        /** Shortest randomized drag inter-event delay, in milliseconds. */
        internal const val MIN_DRAG_DELAY_MILLIS: Long = 120

        /** Longest randomized drag inter-event delay, in milliseconds. */
        internal const val MAX_DRAG_DELAY_MILLIS: Long = 300

        /** Inter-event delays a center drop paces. */
        internal const val CENTER_DRAG_DELAYS: Int = 4

        /** Inter-event delays a positioned (top/bottom) drop paces. */
        internal const val POSITIONED_DRAG_DELAYS: Int = 6

        /** The base library's delay-policy bucket for a button press/release. */
        internal const val CLICK_BUCKET: String = "click"

        /** The base library's delay-policy bucket for a key press. */
        internal const val PRESS_BUCKET: String = "press"

        /** The base library's delay-policy bucket for a typed character. */
        internal const val TYPE_BUCKET: String = "type"
    }
}
