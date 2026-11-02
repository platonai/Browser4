package ai.platon.browser4.behavior.defaults.humanization

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.humanization.ClickPointRequest
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.DragPlan
import ai.platon.browser4.behavior.humanization.DragRequest
import ai.platon.browser4.behavior.humanization.InteractionHumanizer
import ai.platon.browser4.behavior.humanization.PointerMovePlan
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import ai.platon.browser4.behavior.humanization.PointerPosition
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The community/default interaction-humanization policy.
 *
 * It is the pacing Browser4 has always shipped, now behind the [InteractionHumanizer] seam,
 * and it is deliberately conservative: randomness where a constant would fingerprint the
 * automation, but nothing that changes *what* an interaction does.
 *
 * | Decision | Default behaviour | Why |
 * |---|---|---|
 * | pointer trajectory | ~20 px per step, 3–30 steps, 8–16 ms per step | one teleporting `mousemove` per click is a signal mouse-tracking detectors score directly; this crosses a viewport in a human-like 100–500 ms |
 * | click point | element center nudged by up to ±2 px, clamped inside the box | the exact same pixel on every request for the same element is a fingerprint; the clamp keeps CSS `:hover` and avoids clicking an overlay |
 * | button hold | the `click` delay bucket (90–180 ms by default) | a press released in the same millisecond is a bot fingerprint |
 * | multi-click gap | the `click` delay bucket | a real double click has a human gap between rounds, not a 0 ms burst |
 * | key press | the `press` delay bucket | keeps the base library's pacing policy in charge |
 * | typed character | the `type` bucket, doubled for a surrogate pair | `Input.insertText` with a 0 ms delay drops `input` events, and an astral character needs the extra time |
 * | drag sequence | press/release points jittered ±2 px (center drops only), 120–300 ms between events, 4 events for a center drop and 6 for a positioned drop | constant centers and fixed delays fingerprint a synthetic drag; positioned drops must stay on the live rect edge, and their sweep adds two events |
 *
 * Determinism: the class takes its [Random] as a constructor parameter (default
 * [Random.Default]), so the policy is unit-testable without a browser and a custom edition can
 * seed it.
 *
 * Registered through `META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer`,
 * so `browser4-browser` discovers it without a compile-time dependency on this module.
 */
class DefaultInteractionHumanizer(
    private val random: Random = Random.Default,
) : InteractionHumanizer {
    override val id: String = ID
    override val edition: String = BrowserBehaviorEdition.DEFAULT
    override val priority: Int = 0

    /**
     * The upstream behaviour teleports the pointer (`steps = 1`); this policy walks it at
     * roughly one `mousemove` per 20 px so the page sees a trail, not a jump.
     *
     * The step count is clamped to [MIN_POINTER_STEPS]..[MAX_POINTER_STEPS]: a very short
     * hop still reads as a move (three events), and a viewport-crossing travel cannot produce
     * a flood of events.
     */
    override fun pointerMove(request: PointerMoveRequest, delays: DelaySource): PointerMovePlan {
        val distance = hypot(request.toX - request.fromX, request.toY - request.fromY)
        val steps = (distance / PIXELS_PER_STEP).roundToInt().coerceIn(MIN_POINTER_STEPS, MAX_POINTER_STEPS)
        return PointerMovePlan(
            steps = steps,
            delayMillis = random.nextLong(MIN_STEP_DELAY_MILLIS, MAX_STEP_DELAY_MILLIS + 1),
        )
    }

    /**
     * Nudge the element center by a random offset of at most
     * `min(jitter, box/2 - inset)` per axis, so the result stays inside the element.
     *
     * An element whose box is unknown (`0`) or too small to hold any offset keeps the exact
     * center: moving the pointer outside the element would break the CSS `:hover` state the
     * pointer move exists to establish, which is worse than a repeatable coordinate.
     */
    override fun clickPoint(request: ClickPointRequest): PointerPosition {
        val maxOffset = maxOffset(
            request.width,
            request.height,
            POINTER_JITTER_PX,
            POINTER_JITTER_EDGE_INSET_PX,
        )
        if (maxOffset <= 0.0) {
            return PointerPosition(request.x, request.y)
        }
        return PointerPosition(
            x = request.x + random.nextDouble(-maxOffset, maxOffset),
            y = request.y + random.nextDouble(-maxOffset, maxOffset),
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
     * The doubling is not cosmetic: a surrogate pair is inserted as two UTF-16 units by the
     * renderer, and the original driver needed the extra time for the second unit to land
     * before the next `insertText` (a 0 ms delay drops `input` events — see AGENTS.md).
     */
    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long {
        val delay = delays.delayMillis(TYPE_BUCKET)
        return if (codePoint.length > 1) delay * 2 else delay
    }

    /**
     * Jitter the press/release points and randomize the inter-event delays of the drag.
     *
     * A positioned drop (`top`/`bottom`) pins its target point to the live element rect: the
     * page script reads the edge region at dispatch time, and a jittered edge point makes
     * insert-before/insert-after a coin flip on child-index-based reorder lists.
     */
    override fun dragPlan(request: DragRequest, delays: DelaySource): DragPlan {
        val sourceX = request.sourceX + randomOffset(DRAG_POINT_JITTER_PX)
        val sourceY = request.sourceY + randomOffset(DRAG_POINT_JITTER_PX)
        val targetX: Double
        val targetY: Double
        if (request.positioned) {
            targetX = request.targetX
            targetY = request.targetY
        } else {
            targetX = request.targetX + randomOffset(DRAG_POINT_JITTER_PX)
            targetY = request.targetY + randomOffset(DRAG_POINT_JITTER_PX)
        }

        // A positioned drop sweeps two extra dragover events, so it paces six delays.
        val delayCount = if (request.positioned) POSITIONED_DRAG_DELAYS else CENTER_DRAG_DELAYS
        val dragDelays = List(delayCount) {
            random.nextLong(MIN_DRAG_DELAY_MILLIS, MAX_DRAG_DELAY_MILLIS + 1)
        }
        return DragPlan(
            sourceX = sourceX,
            sourceY = sourceY,
            targetX = targetX,
            targetY = targetY,
            delays = dragDelays,
        )
    }

    /** Uniform random offset in `[-range, range]`. */
    private fun randomOffset(range: Double): Double = random.nextDouble(-range, range)

    companion object {
        /** Provider id used by configuration and diagnostics. */
        const val ID: String = "default"

        /** Pixels of pointer travel per intermediate `mousemove`. */
        internal const val PIXELS_PER_STEP: Double = 20.0

        /** Minimum intermediate moves — a short hop must still look like a move. */
        internal const val MIN_POINTER_STEPS: Int = 3

        /** Maximum intermediate moves — a viewport-crossing travel must not flood events. */
        internal const val MAX_POINTER_STEPS: Int = 30

        /** Wait between two `mousemove` dispatches, inclusive. */
        internal const val MIN_STEP_DELAY_MILLIS: Long = 8

        /** Wait between two `mousemove` dispatches, inclusive. */
        internal const val MAX_STEP_DELAY_MILLIS: Long = 16

        /**
         * Maximum pointer jitter (CSS pixels) applied before a click-family pointer move.
         *
         * Landing on the element's exact center every single time is itself a fingerprint, so
         * the pointer is nudged the same way the drag sequence already nudges its
         * press/release points (±2 px). Explicit coordinates from `mouse-move` are never
         * jittered — the caller asked for a specific point.
         */
        internal const val POINTER_JITTER_PX: Double = 2.0

        /** Keep a jittered pointer this far inside the element box. */
        internal const val POINTER_JITTER_EDGE_INSET_PX: Double = 1.0

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

        /**
         * The largest jitter a box of [width] × [height] can hold on both axes, or `0` when
         * the box is unknown or too small to keep [inset] inside every edge.
         */
        private fun maxOffset(width: Double, height: Double, jitter: Double, inset: Double): Double {
            if (width <= 0.0 || height <= 0.0) {
                return 0.0
            }
            return min(jitter, min(width, height) / 2.0 - inset).coerceAtLeast(0.0)
        }
    }
}
