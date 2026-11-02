package ai.platon.browser4.behavior.defaults.humanization

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.humanization.ClickPointRequest
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.DragRequest
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Unit tests for the default edition's [DefaultInteractionHumanizer].
 *
 * The policy is pure arithmetic on a [Random], so every test injects a seeded
 * [kotlin.random.Random] and asserts the exact formulas the driver used to apply inline:
 * the pointer step count and per-step delay, the clamped click jitter, the bucket-based
 * pacing, and the drag plan's point jitter/edge pinning and delay count. A regression here is
 * a silently changed fingerprint surface, which no E2E assertion would catch.
 */
@DisplayName("DefaultInteractionHumanizer")
class DefaultInteractionHumanizerTest {

    private fun humanizer(seed: Int = 7) = DefaultInteractionHumanizer(Random(seed))

    /** A delay source that records the requested bucket and returns [value]. */
    private class RecordingDelaySource(private val value: Long = 100L) : DelaySource {
        val buckets = mutableListOf<String>()

        override fun delayMillis(bucket: String): Long {
            buckets += bucket
            return value
        }
    }

    @Test
    @DisplayName("metadata identifies the default edition for registry selection")
    fun metadataIdentifiesTheDefaultEdition() {
        val humanizer = DefaultInteractionHumanizer()

        assertEquals("default", humanizer.id)
        assertEquals(BrowserBehaviorEdition.DEFAULT, humanizer.edition)
        assertEquals(0, humanizer.priority, "an edition module installed next to it must outrank it")
    }

    // -------------------------------------------------------------------------
    // Pointer trajectory
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the pointer walks ~20 px per step instead of teleporting")
    fun pointerWalkScalesWithDistance() {
        val plan = humanizer().pointerMove(PointerMoveRequest(0.0, 0.0, 100.0, 0.0), RecordingDelaySource())

        assertEquals(5, plan.steps, "100 px at 20 px per step")
    }

    @Test
    @DisplayName("a short hop still dispatches the minimum number of moves")
    fun shortHopKeepsMinimumSteps() {
        val plan = humanizer().pointerMove(PointerMoveRequest(10.0, 10.0, 12.0, 10.0), RecordingDelaySource())

        assertEquals(DefaultInteractionHumanizer.MIN_POINTER_STEPS, plan.steps)
    }

    @Test
    @DisplayName("a viewport-crossing travel is capped, not flooded with moves")
    fun longTravelIsCapped() {
        val plan = humanizer().pointerMove(PointerMoveRequest(0.0, 0.0, 5000.0, 3000.0), RecordingDelaySource())

        assertEquals(DefaultInteractionHumanizer.MAX_POINTER_STEPS, plan.steps)
    }

    @Test
    @DisplayName("the step count matches the documented one-step-per-20px rule")
    fun stepCountMatchesTheDocumentedRule() {
        // The former Browser4WebDriver.pointerMoveSteps assertions, moved with the formula.
        fun stepsTo(distance: Double): Int = humanizer()
            .pointerMove(PointerMoveRequest(0.0, 0.0, distance, 0.0), RecordingDelaySource())
            .steps

        assertEquals(7, stepsTo(140.0))
        assertEquals(20, stepsTo(400.0))
        assertEquals(3, stepsTo(0.0))
        assertEquals(3, stepsTo(39.0))
        assertEquals(30, stepsTo(600.0))
        assertEquals(30, stepsTo(100_000.0))
    }

    @Test
    @DisplayName("the per-step delay stays inside the human-like window")
    fun perStepDelayStaysInWindow() {
        repeat(50) {
            val plan = humanizer(seed = it).pointerMove(
                PointerMoveRequest(0.0, 0.0, 400.0, 300.0),
                RecordingDelaySource(),
            )
            assertTrue(
                plan.delayMillis in
                    DefaultInteractionHumanizer.MIN_STEP_DELAY_MILLIS..DefaultInteractionHumanizer.MAX_STEP_DELAY_MILLIS,
                "unexpected per-step delay: ${plan.delayMillis}",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Click point jitter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clickPoint stays inside a large element")
    fun clickPointStaysInsideLargeElement() {
        val point = humanizer().clickPoint(ClickPointRequest(100.0, 50.0, 200.0, 40.0))

        // 200x40 element: the full ±2 px jitter is allowed (half of 40 px minus the 1 px inset).
        assertTrue(abs(point.x - 100.0) <= DefaultInteractionHumanizer.POINTER_JITTER_PX, "x=${point.x}")
        assertTrue(abs(point.y - 50.0) <= DefaultInteractionHumanizer.POINTER_JITTER_PX, "y=${point.y}")
    }

    @Test
    @DisplayName("clickPoint shrinks the offset for a small element")
    fun clickPointShrinksOffsetForSmallElement() {
        // 4x4 element: half of the box minus the 1 px inset leaves 1 px instead of 2 px.
        repeat(100) {
            val point = humanizer(seed = it).clickPoint(ClickPointRequest(10.0, 10.0, 4.0, 4.0))
            assertTrue(abs(point.x - 10.0) <= 1.0, "x=${point.x} escaped the 4 px box")
            assertTrue(abs(point.y - 10.0) <= 1.0, "y=${point.y} escaped the 4 px box")
        }
    }

    @Test
    @DisplayName("clickPoint keeps the exact center when the box is unknown")
    fun clickPointKeepsCenterWithoutBox() {
        val point = humanizer().clickPoint(ClickPointRequest(7.0, 9.0, 0.0, 0.0))

        assertEquals(7.0, point.x)
        assertEquals(9.0, point.y)
    }

    @Test
    @DisplayName("clickPoint never leaves the element box")
    fun clickPointNeverLeavesTheBox() {
        val boxes = listOf(
            200.0 to 40.0,
            13.0 to 13.0,
            6.0 to 6.0,
            5.0 to 40.0,
            1000.0 to 8.0,
        )

        boxes.forEach { (width, height) ->
            repeat(200) {
                val point = humanizer().clickPoint(ClickPointRequest(500.0, 500.0, width, height))
                assertTrue(
                    point.x >= 500.0 - width / 2 && point.x <= 500.0 + width / 2,
                    "x=${point.x} escaped a ${width}x$height box",
                )
                assertTrue(
                    point.y >= 500.0 - height / 2 && point.y <= 500.0 + height / 2,
                    "y=${point.y} escaped a ${width}x$height box",
                )
            }
        }
    }

    @Test
    @DisplayName("clickPoint varies between calls")
    fun clickPointVariesBetweenCalls() {
        val points = (1..20).map { humanizer(seed = it).clickPoint(ClickPointRequest(100.0, 100.0, 200.0, 40.0)) }

        assertTrue(points.any { it != points.first() }, "the pointer position must not be constant")
    }

    // -------------------------------------------------------------------------
    // Pacing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("press hold, multi-click gap and key press read their configured buckets")
    fun pacingReadsTheConfiguredBuckets() {
        val humanizer = humanizer()

        val clickSource = RecordingDelaySource(value = 120L)
        assertEquals(120L, humanizer.pressHoldMillis(clickSource))
        assertEquals(120L, humanizer.multiClickGapMillis(clickSource))
        assertEquals(listOf("click", "click"), clickSource.buckets)

        val pressSource = RecordingDelaySource(value = 90L)
        assertEquals(90L, humanizer.keyPressDelayMillis(pressSource))
        assertEquals(listOf("press"), pressSource.buckets)
    }

    @Test
    @DisplayName("a typed character reads the type bucket, doubled for a surrogate pair")
    fun typedCharacterDoublesForSupplementaryCodePoint() {
        val humanizer = humanizer()
        val source = RecordingDelaySource(value = 100L)

        assertEquals(100L, humanizer.typeCharDelayMillis("a", source))
        assertEquals(200L, humanizer.typeCharDelayMillis("\uD83D\uDE00", source), "an astral character needs twice the time")
        assertEquals(listOf("type", "type"), source.buckets)
    }

    // -------------------------------------------------------------------------
    // Drag plan
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a center drop jitters both points and paces four delays")
    fun centerDropJittersBothPoints() {
        val plan = humanizer().dragPlan(
            DragRequest(sourceX = 100.0, sourceY = 100.0, targetX = 300.0, targetY = 200.0, positioned = false),
            RecordingDelaySource(),
        )

        assertEquals(DefaultInteractionHumanizer.CENTER_DRAG_DELAYS, plan.delays.size)
        assertTrue(abs(plan.sourceX - 100.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX, "sourceX=${plan.sourceX}")
        assertTrue(abs(plan.sourceY - 100.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX, "sourceY=${plan.sourceY}")
        assertTrue(abs(plan.targetX - 300.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX, "targetX=${plan.targetX}")
        assertTrue(abs(plan.targetY - 200.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX, "targetY=${plan.targetY}")
        plan.delays.forEach {
            assertTrue(
                it in DefaultInteractionHumanizer.MIN_DRAG_DELAY_MILLIS..DefaultInteractionHumanizer.MAX_DRAG_DELAY_MILLIS,
                "unexpected drag delay: $it",
            )
        }
    }

    @Test
    @DisplayName("a positioned drop pins the target point and paces six delays")
    fun positionedDropPinsTheTargetPoint() {
        val plan = humanizer().dragPlan(
            DragRequest(sourceX = 100.0, sourceY = 100.0, targetX = 300.0, targetY = 200.0, positioned = true),
            RecordingDelaySource(),
        )

        assertEquals(DefaultInteractionHumanizer.POSITIONED_DRAG_DELAYS, plan.delays.size)
        assertEquals(300.0, plan.targetX, "a jittered edge point makes insert-before/after a coin flip")
        assertEquals(200.0, plan.targetY)
        assertTrue(abs(plan.sourceX - 100.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX)
        assertTrue(abs(plan.sourceY - 100.0) <= DefaultInteractionHumanizer.DRAG_POINT_JITTER_PX)
    }
}
