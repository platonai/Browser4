package ai.platon.pulsar.chrome

import ai.platon.browser4.behavior.humanization.MoveWaypoint
import ai.platon.browser4.behavior.humanization.PointerMovePlan

/**
 * What the driver must dispatch for one planned pointer move.
 *
 * Split out of `Browser4WebDriver.glidePointerTo` so the waypoints contract can be pinned
 * without a browser: the dispatch itself needs a live `Mouse`, but the decision of *which*
 * shape to dispatch, and with which values, is pure.
 */
internal sealed interface PointerDispatch {
    /**
     * Hand the move to the browser library: it bends a path of [steps] segments, waiting
     * [delayMillis] between them. This is the historical behaviour of every provider whose
     * [PointerMovePlan.waypoints] is empty.
     */
    data class Interpolate(
        val x: Double,
        val y: Double,
        val steps: Int,
        val delayMillis: Long,
    ) : PointerDispatch

    /** Walk [points] in order, one single-step move each, after that point's own wait. */
    data class Waypoints(val points: List<MoveWaypoint>) : PointerDispatch
}

/**
 * Choose the dispatch shape for ([x], [y]) from [plan].
 *
 * A non-empty [PointerMovePlan.waypoints] wins and is dispatched verbatim, in order — that is
 * what makes an overshoot or a variable-speed trajectory observable to the page. `steps` and
 * `delayMillis` are then ignored, as documented on [PointerMovePlan].
 */
internal fun pointerDispatchFor(x: Double, y: Double, plan: PointerMovePlan): PointerDispatch =
    if (plan.waypoints.isEmpty()) {
        PointerDispatch.Interpolate(x, y, plan.steps, plan.delayMillis)
    } else {
        PointerDispatch.Waypoints(plan.waypoints)
    }
