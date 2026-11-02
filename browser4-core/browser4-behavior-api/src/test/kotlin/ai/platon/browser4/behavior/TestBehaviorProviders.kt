package ai.platon.browser4.behavior

import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.stealth.BrowserStealthProvider
import ai.platon.browser4.behavior.stealth.StealthLaunchContext
import ai.platon.browser4.behavior.stealth.StealthLaunchReport

/**
 * Test providers for [BrowserBehaviorTest], registered through the test classpath's
 * `META-INF/services` files. They let the registry's selection rules (priority, explicit id,
 * unknown id) be exercised without the real implementation module.
 */
class HighPriorityTestStealthProvider : BrowserStealthProvider {
    override val id: String = ID
    override val edition: String = "test"
    override val priority: Int = 100

    override fun applyLaunchOptions(context: StealthLaunchContext): StealthLaunchReport =
        StealthLaunchReport(providerId = id, edition = edition)

    companion object {
        const val ID: String = "test-high"
    }
}

class LowPriorityTestStealthProvider : BrowserStealthProvider {
    override val id: String = ID
    override val edition: String = "test"
    override val priority: Int = -100

    override fun applyLaunchOptions(context: StealthLaunchContext): StealthLaunchReport =
        StealthLaunchReport(providerId = id, edition = edition)

    companion object {
        const val ID: String = "test-low"
    }
}

class HighPriorityTestHumanizer : ai.platon.browser4.behavior.humanization.InteractionHumanizer {
    override val id: String = ID
    override val edition: String = "test"
    override val priority: Int = 100

    override fun pointerMove(
        request: ai.platon.browser4.behavior.humanization.PointerMoveRequest,
        delays: DelaySource,
    ) = ai.platon.browser4.behavior.humanization.PointerMovePlan(1, 0)

    override fun clickPoint(request: ai.platon.browser4.behavior.humanization.ClickPointRequest) =
        ai.platon.browser4.behavior.humanization.PointerPosition(request.x, request.y)

    override fun pressHoldMillis(delays: DelaySource): Long = 0

    override fun multiClickGapMillis(delays: DelaySource): Long = 0

    override fun keyPressDelayMillis(delays: DelaySource): Long = 0

    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long = 0

    override fun dragPlan(request: ai.platon.browser4.behavior.humanization.DragRequest, delays: DelaySource) =
        ai.platon.browser4.behavior.humanization.DragPlan(
            request.sourceX, request.sourceY, request.targetX, request.targetY, List(4) { 0L }
        )

    companion object {
        const val ID: String = "test-high-humanizer"
    }
}

class LowPriorityTestHumanizer : ai.platon.browser4.behavior.humanization.InteractionHumanizer {
    override val id: String = ID
    override val edition: String = "test"
    override val priority: Int = -100

    override fun pointerMove(
        request: ai.platon.browser4.behavior.humanization.PointerMoveRequest,
        delays: DelaySource,
    ) = ai.platon.browser4.behavior.humanization.PointerMovePlan(1, 0)

    override fun clickPoint(request: ai.platon.browser4.behavior.humanization.ClickPointRequest) =
        ai.platon.browser4.behavior.humanization.PointerPosition(request.x, request.y)

    override fun pressHoldMillis(delays: DelaySource): Long = 0

    override fun multiClickGapMillis(delays: DelaySource): Long = 0

    override fun keyPressDelayMillis(delays: DelaySource): Long = 0

    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long = 0

    override fun dragPlan(request: ai.platon.browser4.behavior.humanization.DragRequest, delays: DelaySource) =
        ai.platon.browser4.behavior.humanization.DragPlan(
            request.sourceX, request.sourceY, request.targetX, request.targetY, List(4) { 0L }
        )

    companion object {
        const val ID: String = "test-low-humanizer"
    }
}
