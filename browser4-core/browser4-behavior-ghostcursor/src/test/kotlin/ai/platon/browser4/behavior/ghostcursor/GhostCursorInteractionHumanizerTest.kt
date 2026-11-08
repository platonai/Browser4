package ai.platon.browser4.behavior.ghostcursor

import ai.platon.browser4.behavior.humanization.ClickPointRequest
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.DragRequest
import ai.platon.browser4.behavior.humanization.IdleMoveRequest
import ai.platon.browser4.behavior.humanization.InteractionHumanizer
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Unit tests for the ghost-cursor policy: everything asserted here is pure decision output, so
 * no browser is involved.
 */
class GhostCursorInteractionHumanizerTest {
    private val fixedDelaySource = DelaySource { 7L }

    @Test
    @DisplayName("pointer move ends exactly on the target")
    fun pointerMoveEndsExactlyOnTheTarget() {
        val humanizer = GhostCursorInteractionHumanizer(Random(11))
        listOf(20.0 to 30.0, 900.0 to 40.0, 3.0 to 5.0).forEach { (targetX, targetY) ->
            val plan = humanizer.pointerMove(
                PointerMoveRequest(0.0, 0.0, targetX, targetY, 80.0),
                fixedDelaySource,
            )
            assertTrue(plan.waypoints.isNotEmpty())
            val last = plan.waypoints.last()
            assertEquals(targetX, last.x, 1e-9)
            assertEquals(targetY, last.y, 1e-9)
        }
    }

    @Test
    @DisplayName("pointer move never plans a negative delay")
    fun pointerMoveNeverPlansANegativeDelay() {
        val humanizer = GhostCursorInteractionHumanizer(Random(12))
        repeat(50) {
            val plan = humanizer.pointerMove(
                PointerMoveRequest(10.0, 20.0, 700.0, 400.0, 60.0),
                fixedDelaySource,
            )
            assertTrue(plan.waypoints.all { it.delayBeforeMillis >= 0 })
            // The whole point of returning waypoints instead of a step count is that the driver
            // dispatches them itself, so the plan must never fall back to `steps`.
            assertEquals(plan.waypoints.size, plan.steps)
            assertEquals(0L, plan.delayMillis)
        }
    }

    @Test
    @DisplayName("a coincident move is a single confirmation point")
    fun coincidentMoveIsASinglePoint() {
        val humanizer = GhostCursorInteractionHumanizer(Random(13))
        val plan = humanizer.pointerMove(
            PointerMoveRequest(42.0, 24.0, 42.0, 24.0),
            fixedDelaySource,
        )
        assertEquals(1, plan.waypoints.size)
        assertEquals(42.0, plan.waypoints.single().x, 1e-9)
        assertEquals(24.0, plan.waypoints.single().y, 1e-9)
    }

    @Test
    @DisplayName("a long move overshoots past the target")
    fun longMoveOvershootsPastTheTarget() {
        val targetX = 900.0
        // The overshoot direction is random, so a single run is not guaranteed to travel past
        // the target: over many seeds at least one must, which is only possible when the model
        // really plans a point beyond it (the straight default never does).
        val overshot = (1..50).any { seed ->
            val humanizer = GhostCursorInteractionHumanizer(Random(seed))
            val plan = humanizer.pointerMove(
                PointerMoveRequest(0.0, 0.0, targetX, 0.0, 100.0),
                fixedDelaySource,
            )
            plan.waypoints.dropLast(1).any { it.x > targetX + 1.0 }
        }
        assertTrue(overshot, "no seed produced a waypoint past the target")
    }

    @Test
    @DisplayName("a short move keeps every point near the target")
    fun shortMoveKeepsEveryPointNearTheTarget() {
        val humanizer = GhostCursorInteractionHumanizer(Random(14))
        repeat(20) {
            val plan = humanizer.pointerMove(
                PointerMoveRequest(0.0, 0.0, 120.0, 0.0, 100.0),
                fixedDelaySource,
            )
            val farthest = plan.waypoints.maxOf { hypot(it.x - 120.0, it.y) }
            assertTrue(
                farthest < GhostCursorTrajectory.OVERSHOOT_THRESHOLD_PX,
                "a move below the overshoot threshold wandered $farthest px from the target",
            )
        }
    }

    @Test
    @DisplayName("step count grows with distance and shrinks with target width")
    fun stepCountFollowsFittsLaw() {
        val trajectory = GhostCursorTrajectory(Random(15))
        val near = trajectory.stepCount(100.0, 100.0)
        val far = trajectory.stepCount(1400.0, 100.0)
        assertTrue(far > near, "a far move should plan more events than a near one: $far vs $near")

        val smallTarget = trajectory.stepCount(800.0, 20.0)
        val largeTarget = trajectory.stepCount(800.0, 400.0)
        assertTrue(
            smallTarget > largeTarget,
            "a small target should plan more events than a large one: $smallTarget vs $largeTarget",
        )
    }

    @Test
    @DisplayName("a tiny move never floods the page with events")
    fun tinyMoveDoesNotFloodEvents() {
        val trajectory = GhostCursorTrajectory(Random(16))
        assertTrue(trajectory.stepCount(2.0, 10.0) <= GhostCursorTrajectory.MIN_STEPS)
        assertTrue(trajectory.stepCount(5000.0, 10.0) <= GhostCursorTrajectory.MAX_STEPS)
    }

    @Test
    @DisplayName("click point lands inside the element box")
    fun clickPointStaysInsideTheBox() {
        val humanizer = GhostCursorInteractionHumanizer(Random(17))
        val request = ClickPointRequest(x = 300.0, y = 200.0, width = 120.0, height = 40.0)
        val left = request.x - request.width / 2
        val top = request.y - request.height / 2
        repeat(200) {
            val point = humanizer.clickPoint(request)
            assertTrue(point.x in left..(left + request.width), "x=${point.x} escaped the box")
            assertTrue(point.y in top..(top + request.height), "y=${point.y} escaped the box")
        }
    }

    @Test
    @DisplayName("a fully padded click box always returns the centre")
    fun fullyPaddedClickBoxReturnsTheCentre() {
        val humanizer = GhostCursorInteractionHumanizer(Random(18), paddingPercentage = 100.0)
        repeat(50) {
            val point = humanizer.clickPoint(ClickPointRequest(300.0, 200.0, 120.0, 40.0))
            assertEquals(300.0, point.x, 1e-9)
            assertEquals(200.0, point.y, 1e-9)
        }
    }

    @Test
    @DisplayName("an unknown or tiny click box degrades to the centre")
    fun unknownClickBoxDegradesToTheCentre() {
        val humanizer = GhostCursorInteractionHumanizer(Random(19))
        assertEquals(300.0, humanizer.clickPoint(ClickPointRequest(300.0, 200.0, 0.0, 0.0)).x, 1e-9)
        assertEquals(200.0, humanizer.clickPoint(ClickPointRequest(300.0, 200.0, 0.0, 0.0)).y, 1e-9)
        val tiny = humanizer.clickPoint(ClickPointRequest(300.0, 200.0, 1.0, 1.0))
        assertEquals(300.0, tiny.x, 1e-9)
        assertEquals(200.0, tiny.y, 1e-9)
    }

    @Test
    @DisplayName("a positioned drag keeps its target exact")
    fun positionedDragKeepsItsTargetExact() {
        val humanizer = GhostCursorInteractionHumanizer(Random(20))
        val plan = humanizer.dragPlan(
            DragRequest(sourceX = 100.0, sourceY = 100.0, targetX = 400.0, targetY = 300.0, positioned = true),
            fixedDelaySource,
        )
        assertEquals(400.0, plan.targetX, 1e-9)
        assertEquals(300.0, plan.targetY, 1e-9)
        assertEquals(GhostCursorInteractionHumanizer.POSITIONED_DRAG_DELAYS, plan.delays.size)
    }

    @Test
    @DisplayName("a center drag jitters its points and paces four events")
    fun centreDragJittersItsPoints() {
        val humanizer = GhostCursorInteractionHumanizer(Random(21))
        val plan = humanizer.dragPlan(
            DragRequest(sourceX = 100.0, sourceY = 100.0, targetX = 400.0, targetY = 300.0, positioned = false),
            fixedDelaySource,
        )
        assertEquals(GhostCursorInteractionHumanizer.CENTER_DRAG_DELAYS, plan.delays.size)
        assertTrue(abs(plan.targetX - 400.0) <= GhostCursorInteractionHumanizer.DRAG_POINT_JITTER_PX)
        assertTrue(abs(plan.targetY - 300.0) <= GhostCursorInteractionHumanizer.DRAG_POINT_JITTER_PX)
        assertTrue(plan.delays.all { it in 120L..300L })
    }

    @Test
    @DisplayName("a supplementary character doubles the type delay")
    fun supplementaryCharacterDoublesTheTypeDelay() {
        val humanizer = GhostCursorInteractionHumanizer(Random(22))
        assertEquals(7L, humanizer.typeCharDelayMillis("a", fixedDelaySource))
        assertEquals(14L, humanizer.typeCharDelayMillis("\uD83D\uDE00", fixedDelaySource))
    }

    @Test
    @DisplayName("idle moves stay inside the viewport")
    fun idleMovesStayInsideTheViewport() {
        val humanizer = GhostCursorInteractionHumanizer(Random(23))
        repeat(100) {
            val plan = humanizer.idleMove(IdleMoveRequest(1280.0, 720.0, 0.0, 0.0))
            assertNotNull(plan)
            plan!!
            assertTrue(plan.x in 0.0..1280.0, "x=${plan.x} escaped the viewport")
            assertTrue(plan.y in 0.0..720.0, "y=${plan.y} escaped the viewport")
            assertTrue(plan.restMillis in 0L..2_000L, "rest=${plan.restMillis} out of range")
        }
    }

    @Test
    @DisplayName("an unknown viewport yields the origin")
    fun unknownViewportYieldsTheOrigin() {
        val planner = GhostCursorIdlePlanner(Random(24))
        assertEquals(0.0, planner.nextPoint(0.0, 0.0).x, 1e-9)
        assertEquals(0.0, planner.nextPoint(0.0, 0.0).y, 1e-9)
    }

    @Test
    @DisplayName("the provider is registered by its id and does not outrank the default")
    fun providerIsRegisteredByIdAndDoesNotOutrankTheDefault() {
        val humanizer: InteractionHumanizer = GhostCursorInteractionHumanizer()
        assertEquals(GhostCursorInteractionHumanizer.ID, humanizer.id)
        assertEquals(0, humanizer.priority)
        // A tie is broken by id ascending, so `default` must still win: installing this module
        // changes nothing until the operator pins the provider.
        assertNotEquals("default", humanizer.id)
    }

    @Test
    @DisplayName("falsey overshoot draws stay inside the overshoot radius")
    fun overshootStaysInsideItsRadius() {
        val random = Random(25)
        repeat(200) {
            val point = GhostCursorMath.overshoot(Vector2(500.0, 400.0), GhostCursorMath.OVERSHOOT_RADIUS, random)
            val distance = hypot(point.x - 500.0, point.y - 400.0)
            assertTrue(distance <= GhostCursorMath.OVERSHOOT_RADIUS + 1e-9, "distance=$distance")
        }
    }

    @Test
    @DisplayName("button, key and multi-click pacing come from the shared buckets")
    fun pacingComesFromTheSharedBuckets() {
        // ghost-cursor presses for 0 ms, which makes mousedown and mouseup land in the same
        // millisecond; using the bucket values instead is the deliberate deviation, so it has to
        // be pinned: a regression to 0 would otherwise be invisible.
        val bucketDelays = DelaySource { bucket ->
            when (bucket) {
                "click" -> 11L
                "press" -> 13L
                else -> 0L
            }
        }
        val humanizer = GhostCursorInteractionHumanizer(Random(26))
        assertEquals(11L, humanizer.pressHoldMillis(bucketDelays))
        assertEquals(11L, humanizer.multiClickGapMillis(bucketDelays))
        assertEquals(13L, humanizer.keyPressDelayMillis(bucketDelays))
    }

    @Test
    @DisplayName("the trajectory emits exactly the step count it plans")
    fun trajectoryEmitsTheStepCountItPlans() {
        val trajectory = GhostCursorTrajectory(Random(27))
        listOf(60.0, 200.0, 480.0).forEach { distance ->
            val plan = trajectory.plan(0.0, 0.0, distance, 0.0, 120.0)
            assertEquals(
                trajectory.stepCount(distance, 120.0),
                plan.size,
                "a $distance px travel dispatched ${plan.size} events",
            )
        }
    }

    @Test
    @DisplayName("the pointer crosses the middle of a travel faster than its ends")
    fun pointerCrossesTheMiddleFasterThanTheEnds() {
        val humanizer = GhostCursorInteractionHumanizer(Random(28))
        val plan = humanizer.pointerMove(PointerMoveRequest(0.0, 0.0, 480.0, 0.0, 120.0), fixedDelaySource)
        val gaps = plan.waypoints.map { it.delayBeforeMillis }
        val middle = gaps[gaps.size / 2]
        assertTrue(middle > gaps.first() + 1, "first gap ${gaps.first()} ms vs middle $middle ms")
        assertTrue(middle > gaps.last() + 1, "last gap ${gaps.last()} ms vs middle $middle ms")
    }

    @Test
    @DisplayName("arc-length timing follows the smooth-step profile")
    fun arcLengthTimingFollowsTheSmoothStepProfile() {
        val anchors = GhostCursorMath.generateBezierAnchors(
            Vector2(0.0, 0.0),
            Vector2(400.0, 0.0),
            40.0,
            Random(29),
        )
        val timed = GhostCursorMath.timedBezierCurve(anchors, samples = 41, totalDurationMillis = 600.0)
        val gaps = timed.zipWithNext { a, b -> b.timeMillis - a.timeMillis }
        val middle = gaps[gaps.size / 2]
        assertTrue(middle > gaps.first() * 3, "middle ${middle} ms vs first ${gaps.first()} ms")
        assertTrue(middle > gaps.last() * 3, "middle ${middle} ms vs last ${gaps.last()} ms")
        assertEquals(0.0, timed.first().timeMillis, 1e-9)
        assertEquals(600.0, timed.last().timeMillis, 1e-9)
    }

    @Test
    @DisplayName("the ServiceLoader discovers the registration file")
    fun serviceLoaderDiscoversTheRegistration() {
        // The other provider tests construct the class directly, which would still pass with a
        // broken or missing META-INF/services entry; this one goes through the real mechanism.
        val ids = ServiceLoader.load(InteractionHumanizer::class.java).map { it.id }.toSet()
        assertTrue(GhostCursorInteractionHumanizer.ID in ids, "installed humanizers: $ids")
    }
}
