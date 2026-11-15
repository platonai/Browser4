package ai.platon.pulsar.osinput.geometry

/**
 * The whole "viewport -> desktop" link in one place.
 *
 * ```
 * device = windowOriginDevice + insets + css * devicePixelRatio
 * awt    = device / awtScale
 * ```
 *
 * Inputs:
 *  - [frame]   — CDP `Browser.getWindowForTarget` bounds (DIP) + the monitor scale.
 *  - [metrics] — `innerWidth/innerHeight/outerWidth/outerHeight/devicePixelRatio` from the page.
 *  - [insets]  — browser chrome between the window edge and the viewport (device px).
 *
 * The mapping is exact only when [insets] are calibrated; [ChromeInsets.derived] gives a
 * good first approximation, and [calibrate] turns one known on-screen reference into exact
 * insets. Browser4 itself has no OS coordinate space, so this arithmetic is the bridge that
 * a plugin has to own (see `docs/viewport-to-screen.md`).
 */
class ViewportScreenMapper(
    val frame: WindowFrame,
    val metrics: ViewportMetrics,
    val insets: ChromeInsets = ChromeInsets.EMPTY,
) {
    /** CSS px -> device px. Folds page zoom *and* display scale, exactly like Chrome. */
    val deviceScale: Double get() = metrics.devicePixelRatio

    /** Physical screen position of the viewport's top-left corner (device px). */
    val viewportOrigin: ScreenPoint = ScreenPoint(
        frame.physicalLeft + insets.left + metrics.visualViewportOffsetX * deviceScale,
        frame.physicalTop + insets.top + metrics.visualViewportOffsetY * deviceScale,
    )

    /** The viewport, in device px, clipped conceptually to the window it belongs to. */
    val viewportRect: ScreenRect = ScreenRect(
        viewportOrigin.x,
        viewportOrigin.y,
        metrics.viewportWidthDevice,
        metrics.viewportHeightDevice,
    )

    /** True when the derived viewport fits inside the reported window frame. */
    val isConsistent: Boolean
        get() = viewportRect.x >= frame.physicalLeft - 0.5 &&
            viewportRect.y >= frame.physicalTop - 0.5 &&
            viewportRect.right <= frame.physicalLeft + frame.physicalWidth + 0.5 &&
            viewportRect.bottom <= frame.physicalTop + frame.physicalHeight + 0.5

    /** Viewport CSS px -> physical screen px (what `SendInput` consumes). */
    fun viewportToScreen(cssX: Double, cssY: Double): ScreenPoint = ScreenPoint(
        viewportOrigin.x + cssX * deviceScale,
        viewportOrigin.y + cssY * deviceScale,
    )

    fun viewportToScreen(point: ScreenPoint): ScreenPoint = viewportToScreen(point.x, point.y)

    /** Inverse mapping, useful for checking a screenshot-derived reference point. */
    fun screenToViewport(screen: ScreenPoint): ScreenPoint = ScreenPoint(
        (screen.x - viewportOrigin.x) / deviceScale,
        (screen.y - viewportOrigin.y) / deviceScale,
    )

    /** Physical screen px -> AWT user space (`java.awt.Robot`, `MouseInfo`). */
    fun screenToAwt(screen: ScreenPoint, awtScale: Double = 1.0): ScreenPoint {
        require(awtScale > 0.0) { "awtScale must be > 0: $awtScale" }
        return ScreenPoint(screen.x / awtScale, screen.y / awtScale)
    }

    /** AWT user space -> physical screen px. */
    fun awtToScreen(awt: ScreenPoint, awtScale: Double = 1.0): ScreenPoint {
        require(awtScale > 0.0) { "awtScale must be > 0: $awtScale" }
        return ScreenPoint(awt.x * awtScale, awt.y * awtScale)
    }

    /** Everything a caller needs to explain a click in a log or an error message. */
    fun describe(): Map<String, Any?> = mapOf(
        "frame" to frame.toMap(),
        "metrics" to metrics.toMap(),
        "insets" to insets.toMap(),
        "deviceScale" to deviceScale,
        "viewportOriginDevice" to viewportOrigin.toMap(),
        "viewportRectDevice" to viewportRect.toMap(),
        "consistent" to isConsistent,
    )

    override fun toString(): String =
        "ViewportScreenMapper(origin=$viewportOrigin, scale=$deviceScale, viewport=$viewportRect)"

    companion object {
        /**
         * Exact insets from a single known reference: a UI point whose viewport CSS
         * coordinate and physical screen coordinate are both known (for example the last
         * click that landed correctly, or a template match on a screenshot).
         */
        fun calibrate(
            frame: WindowFrame,
            metrics: ViewportMetrics,
            referenceCss: ScreenPoint,
            referenceScreen: ScreenPoint,
        ): ChromeInsets {
            val left = referenceScreen.x - frame.physicalLeft - referenceCss.x * metrics.devicePixelRatio
            val top = referenceScreen.y - frame.physicalTop - referenceCss.y * metrics.devicePixelRatio
            val right = (frame.physicalWidth - metrics.viewportWidthDevice - left).coerceAtLeast(0.0)
            val bottom = (frame.physicalHeight - metrics.viewportHeightDevice - top).coerceAtLeast(0.0)
            return ChromeInsets(left, top, right, bottom)
        }

        /** Convenience factory: derive the insets and map in one step. */
        fun derived(
            frame: WindowFrame,
            metrics: ViewportMetrics,
            assumedBottomInset: Double = 0.0,
        ): ViewportScreenMapper = ViewportScreenMapper(
            frame,
            metrics,
            ChromeInsets.derived(frame, metrics, assumedBottomInset),
        )
    }
}
