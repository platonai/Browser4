package ai.platon.browser4.behavior.humanization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the defaults that make the waypoints/targetWidth/idleMove additions backward compatible:
 * a provider written before those members existed must keep compiling and behaving as it did.
 */
class InteractionHumanizerDefaultsTest {
    @Test
    @DisplayName("a pointer move plan defaults to no explicit trajectory")
    fun pointerMovePlanDefaultsToNoTrajectory() {
        val plan = PointerMovePlan(steps = 4, delayMillis = 9)
        assertTrue(plan.waypoints.isEmpty(), "an omitted trajectory must leave the interpolation path in use")
    }

    @Test
    @DisplayName("a pointer move request defaults its target width to unknown")
    fun pointerMoveRequestDefaultsItsTargetWidthToUnknown() {
        val request = PointerMoveRequest(fromX = 0.0, fromY = 0.0, toX = 10.0, toY = 10.0)
        assertEquals(0.0, request.targetWidth, 0.0)
    }

    @Test
    @DisplayName("a provider that predates idle moves keeps working unchanged")
    fun providerThatPredatesIdleMovesKeepsWorking() {
        val humanizer: InteractionHumanizer = MinimalHumanizer()
        assertNull(humanizer.idleMove(IdleMoveRequest(1280.0, 720.0, 0.0, 0.0)))
        // Everything else still resolves: the added member is the only gap.
        val plan = humanizer.pointerMove(PointerMoveRequest(0.0, 0.0, 10.0, 10.0), DelaySource { 0L })
        assertEquals(1, plan.steps)
        assertTrue(plan.waypoints.isEmpty())
    }

    @Test
    @DisplayName("the neutral fallback does not plan a trajectory either")
    fun neutralFallbackDoesNotPlanATrajectory() {
        val plan = NeutralInteractionHumanizer.pointerMove(PointerMoveRequest(0.0, 0.0, 400.0, 300.0), DelaySource { 0L })
        assertTrue(plan.waypoints.isEmpty())
        assertEquals(1, plan.steps)
    }

    /** A humanizer written against the SPI before `idleMove` existed. */
    private class MinimalHumanizer : InteractionHumanizer {
        override val id: String = "minimal"

        override fun pointerMove(request: PointerMoveRequest, delays: DelaySource) =
            PointerMovePlan(steps = 1, delayMillis = 0)

        override fun clickPoint(request: ClickPointRequest) = PointerPosition(request.x, request.y)

        override fun pressHoldMillis(delays: DelaySource): Long = 0

        override fun multiClickGapMillis(delays: DelaySource): Long = 0

        override fun keyPressDelayMillis(delays: DelaySource): Long = 0

        override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long = 0

        override fun dragPlan(request: DragRequest, delays: DelaySource) = DragPlan(
            sourceX = request.sourceX,
            sourceY = request.sourceY,
            targetX = request.targetX,
            targetY = request.targetY,
            delays = List(4) { 0L },
        )
    }
}
