package ai.platon.browser4.behavior.humanization

import ai.platon.browser4.behavior.BrowserBehaviorEdition

/**
 * Produces a random delay, in milliseconds, for a named interaction bucket.
 *
 * This is the bridge between a [InteractionHumanizer] and the browser library's delay policy:
 * `BrowserSettings.interactSettings.delayPolicy` maps a bucket name (`click`, `type`,
 * `press`, ...) to an `IntRange` chosen by the `delay.preset` configuration, and the driver
 * resolves a bucket to a random value inside that range. Passing the source instead of a
 * fixed number keeps a humanizer compatible with every configured preset — the professional
 * implementation can still use the operator's own pacing policy as the baseline and add its
 * own distribution on top.
 *
 * A bucket without an explicit range still resolves: the library falls back to the `default`
 * bucket of the policy.
 */
fun interface DelaySource {
    /**
     * @param bucket interaction bucket name (`click`, `type`, `press`, `default`, ...).
     * @return a delay in milliseconds, never negative.
     */
    fun delayMillis(bucket: String): Long
}

/**
 * The replaceable interaction-humanization contract.
 *
 * A humanizer owns *how input behaves in time and space*: how long a button is held, how a
 * pointer crosses the viewport, where inside an element a click lands, how fast characters
 * are inserted. It does not own *which* CDP call implements an interaction — trusted input,
 * dialog handling, frame resolution and the fallbacks to synthetic DOM events stay in
 * `Browser4WebDriver`, because those are correctness mechanisms rather than pacing.
 *
 * Every method is a pure computation for a single decision. The driver performs the delay or
 * the dispatch itself, so a humanizer cannot block a coroutine or reach the browser.
 *
 * ## What a provider must preserve
 *
 * - **Semantics.** The element selected by the caller must stay the element that is clicked,
 *   and the text passed by the caller must be the text that is inserted. A plan that moves
 *   the pointer outside the element box breaks `:hover` and can click an overlay.
 * - **Bounds.** `steps` must be at least 1 and delays must be non-negative; the driver passes
 *   them to CDP unchanged.
 * - **Idempotence per decision.** Each call may be made once per interaction; there is no
 *   batching and no state to carry between calls.
 * - **Thread safety.** One provider instance is shared by every driver in the JVM, and the
 *   click/type/drag methods of different sessions run concurrently — an implementation must be
 *   stateless or synchronize its own state.
 *
 * ## Discovering and selecting a provider
 *
 * Implementations are discovered with [java.util.ServiceLoader]: register the FQCN in
 * `META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer`.
 * The default (community) implementation is `DefaultInteractionHumanizer` in
 * `browser4-behavior-default`; a professional or custom edition replaces it by being the
 * only installed module, or by setting a higher [priority] or by pinning its [id] through
 * [ai.platon.browser4.common.B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER].
 *
 * @see ai.platon.browser4.behavior.BrowserBehavior
 * @see ai.platon.browser4.behavior.stealth.BrowserStealthProvider
 */
interface InteractionHumanizer {
    /**
     * Stable provider id, used to select this humanizer from configuration
     * ([ai.platon.browser4.common.B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER]).
     */
    val id: String

    /** The product edition this provider belongs to, one of [BrowserBehaviorEdition]. */
    val edition: String
        get() = BrowserBehaviorEdition.DEFAULT

    /**
     * Selection order when several providers are installed: a larger value wins.
     *
     * The community implementation uses `0`; an edition module installed next to it uses a
     * positive value.
     */
    val priority: Int
        get() = 0

    /**
     * Plan the pointer movement from ([PointerMoveRequest.fromX], [PointerMoveRequest.fromY])
     * to ([PointerMoveRequest.toX], [PointerMoveRequest.toY]).
     *
     * The plan decides how many intermediate `mousemove` events the page observes and how
     * long the browser waits between them. A single step (the upstream behaviour) teleports
     * the pointer, which a mouse-tracking detector reads as non-human input.
     *
     * A humanizer that wants full control of the path returns an explicit
     * [PointerMovePlan.waypoints] trajectory (overshoot, variable speed) instead of a step
     * count; the driver then dispatches exactly those points.
     *
     * @param delays the configured delay source, for implementations that want to reuse the
     *   operator's own pacing policy instead of hard-coding it.
     */
    fun pointerMove(request: PointerMoveRequest, delays: DelaySource): PointerMovePlan

    /**
     * Decide the exact viewport point a click-family interaction should press at, given the
     * element's resolved center ([ClickPointRequest.x], [ClickPointRequest.y]) and its box
     * size.
     *
     * The result must stay inside the element box (and therefore inside the CSS `:hover`
     * area); returning the exact center unchanged is valid and means "no jitter".
     */
    fun clickPoint(request: ClickPointRequest): PointerPosition

    /**
     * How long a mouse button is held down before it is released.
     *
     * A press released within the same millisecond is a bot fingerprint; the default
     * implementation uses the `click` delay bucket (90-180 ms by default).
     */
    fun pressHoldMillis(delays: DelaySource): Long

    /**
     * The gap between the rounds of a multi-click (double-click and above).
     *
     * A real double click has a human gap between rounds rather than a 0 ms burst; the
     * default implementation uses the `click` delay bucket.
     */
    fun multiClickGapMillis(delays: DelaySource): Long

    /**
     * The delay after a single key press (including the key-up of a printable character).
     *
     * The default implementation uses the `press` delay bucket.
     */
    fun keyPressDelayMillis(delays: DelaySource): Long

    /**
     * The delay after one inserted character.
     *
     * `Input.insertText` with a 0 ms delay drops `input` events on some pages, so a humanizer
     * must return a usable delay even when it does not try to look human. The default
     * implementation uses the `type` delay bucket and doubles it for a supplementary code
     * point (`codePoint.length > 1`), which gives the renderer time for an astral character.
     *
     * @param codePoint one code point of the typed text, as produced by the driver's
     *   code-point walk; `length` is 1 for BMP characters and 2 for a surrogate pair.
     */
    fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long

    /**
     * Plan the synthetic drag-and-drop sequence: the press point, the release point and the
     * inter-event delays.
     *
     * [DragRequest.positioned] is `true` for a `top`/`bottom` drop, whose point is pinned to
     * the live target rect and must not be jittered (a jittered edge point makes
     * insert-before/insert-after a coin flip). A center drop may jitter both points.
     *
     * The returned [DragPlan.delays] list must have exactly `6` entries for a positioned drop
     * and `4` for a center drop: the driver's drag script paces six/four inter-event steps and
     * rejects a list of any other length.
     */
    fun dragPlan(request: DragRequest, delays: DelaySource): DragPlan

    /**
     * Plan the next idle pointer wander, or `null` when this humanizer has no idle behaviour.
     *
     * A session that sits idle presents a cursor frozen at one coordinate forever, which a
     * behavioural detector reads as a signal. A humanizer that models this returns a new point
     * (and how long to rest there) once the driver decides the session is idle; the driver owns
     * the coroutine that applies it and stops it as soon as a real interaction starts, so this
     * method stays a pure decision like every other one.
     *
     * The default returns `null`: no pointer movement happens unless a provider asks for it.
     * The driver only starts the wander when the operator enables it
     * (`browser.behavior.humanization.idle.enabled`), so an installed provider still changes
     * nothing by default.
     */
    fun idleMove(request: IdleMoveRequest): IdleMovePlan? = null
}

/**
 * @param fromX the pointer's current x, in CSS pixels.
 * @param fromY the pointer's current y.
 * @param toX the destination x.
 * @param toY the destination y.
 * @param targetWidth the width of the element the pointer is travelling to, in CSS pixels;
 *   `0` when the move has no element target (an explicit `mouse-move`, an idle wander). It lets
 *   a humanizer apply a Fitts-law-style pacing that depends on how small the target is rather
 *   than on distance alone.
 */
data class PointerMoveRequest(
    val fromX: Double,
    val fromY: Double,
    val toX: Double,
    val toY: Double,
    val targetWidth: Double = 0.0,
)

/**
 * One intermediate pointer position of a planned trajectory.
 *
 * @param x the viewport x, in CSS pixels.
 * @param y the viewport y.
 * @param delayBeforeMillis the wait before this point is dispatched; must not be negative.
 */
data class MoveWaypoint(
    val x: Double,
    val y: Double,
    val delayBeforeMillis: Long,
)

/**
 * @param steps the number of intermediate `mousemove` dispatches; must be at least 1.
 * @param delayMillis the wait between two dispatches, in milliseconds; must not be negative.
 * @param waypoints an explicit trajectory, in dispatch order.
 *
 * When [waypoints] is non-empty the driver walks it point by point instead of letting the
 * browser library interpolate [steps] even segments: it waits [MoveWaypoint.delayBeforeMillis]
 * and then dispatches a single-step move to the point, so the page observes exactly this path
 * and exactly this pacing. [steps] and [delayMillis] are then ignored.
 *
 * An empty list (the default) keeps the historical contract — [steps] interpolated by
 * `Mouse.moveTo` with [delayMillis] between segments — so an implementation that does not plan
 * a trajectory is unaffected.
 */
data class PointerMovePlan(
    val steps: Int,
    val delayMillis: Long,
    val waypoints: List<MoveWaypoint> = emptyList(),
)

/**
 * @param x the resolved element center x, in CSS pixels.
 * @param y the resolved element center y.
 * @param width the element box width; `0` when unknown.
 * @param height the element box height; `0` when unknown.
 */
data class ClickPointRequest(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** A viewport point in CSS pixels. */
data class PointerPosition(
    val x: Double,
    val y: Double,
)

/**
 * @param sourceX the drag source's resolved center x.
 * @param sourceY the drag source's resolved center y.
 * @param targetX the drop target's resolved center x.
 * @param targetY the drop target's resolved center y.
 * @param positioned `true` for a `top`/`bottom` drop, whose point must stay on the live rect.
 */
data class DragRequest(
    val sourceX: Double,
    val sourceY: Double,
    val targetX: Double,
    val targetY: Double,
    val positioned: Boolean,
)

/**
 * @param sourceX the press point x the drag script receives.
 * @param sourceY the press point y.
 * @param targetX the release point x.
 * @param targetY the release point y.
 * @param delays inter-event delays in milliseconds (exactly 6 for a positioned drop, 4 otherwise).
 */
data class DragPlan(
    val sourceX: Double,
    val sourceY: Double,
    val targetX: Double,
    val targetY: Double,
    val delays: List<Long>,
)

/**
 * @param viewportWidth the viewport width, in CSS pixels.
 * @param viewportHeight the viewport height, in CSS pixels.
 * @param fromX the pointer's current x, so a plan can avoid a no-op move.
 * @param fromY the pointer's current y.
 */
data class IdleMoveRequest(
    val viewportWidth: Double,
    val viewportHeight: Double,
    val fromX: Double,
    val fromY: Double,
)

/**
 * @param x the point the idle wander should move to, in CSS pixels.
 * @param y the point the idle wander should move to.
 * @param restMillis how long to rest at that point before asking for the next wander.
 */
data class IdleMovePlan(
    val x: Double,
    val y: Double,
    val restMillis: Long,
)

/**
 * The upstream-timing humanizer used when no provider is installed on the classpath.
 *
 * It keeps every interaction working and every base-library delay policy in effect, but does
 * not add jitter or a pointer trajectory: the pointer moves in one step, and pacing comes
 * from the configured buckets. The registry logs a warning when this fallback is active,
 * because a product build that lost the behavior module by accident should be visible.
 */
internal object NeutralInteractionHumanizer : InteractionHumanizer {
    const val ID: String = "none"

    override val id: String = ID
    override val priority: Int = Int.MIN_VALUE

    override fun pointerMove(request: PointerMoveRequest, delays: DelaySource): PointerMovePlan =
        PointerMovePlan(steps = 1, delayMillis = 0)

    override fun clickPoint(request: ClickPointRequest): PointerPosition =
        PointerPosition(request.x, request.y)

    override fun pressHoldMillis(delays: DelaySource): Long = 0

    override fun multiClickGapMillis(delays: DelaySource): Long = 0

    override fun keyPressDelayMillis(delays: DelaySource): Long = delays.delayMillis("press")

    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long =
        delays.delayMillis("type")

    override fun dragPlan(request: DragRequest, delays: DelaySource): DragPlan {
        // The drag script requires 4 delays for a center drop and 6 for a positioned one.
        val count = if (request.positioned) POSITIONED_DRAG_DELAYS else CENTER_DRAG_DELAYS
        return DragPlan(
            sourceX = request.sourceX,
            sourceY = request.sourceY,
            targetX = request.targetX,
            targetY = request.targetY,
            delays = List(count) { 0L },
        )
    }

    private const val CENTER_DRAG_DELAYS = 4
    private const val POSITIONED_DRAG_DELAYS = 6
}
