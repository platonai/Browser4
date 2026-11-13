package ai.platon.pulsar.osinput.geometry

/**
 * The coordinate spaces that meet in a cross-application click.
 *
 *  - **CSS / viewport px** — what `snapshot` boxes and `mouseMove(x, y)` use. Origin is the
 *    top-left corner of the page viewport (the "content area"), y grows downwards.
 *  - **device px** — physical desktop pixels. Win32 `SendInput` / `SetCursorPos` take these
 *    when the calling process is per-monitor DPI aware.
 *  - **AWT user space** — what `java.awt.Robot` and `MouseInfo` take. On Windows the JDK
 *    enables per-monitor DPI awareness and reports/scales AWT coordinates by the monitor
 *    transform, so `awt = device / scale`.
 *
 * Mixing these up produces a click that is off by the title-bar height, by the display
 * scale, or by the page zoom — the three failure modes this prototype exists to avoid.
 */
data class ScreenPoint(val x: Double, val y: Double) {
    fun toMap(): Map<String, Any?> = mapOf("x" to x, "y" to y)

    /** The pixel a driver should hit: rounded half-up, never truncated. */
    fun rounded(): ScreenPoint = ScreenPoint(kotlin.math.round(x), kotlin.math.round(y))

    override fun toString(): String = "(%.1f, %.1f)".format(x, y)
}

/** A rectangle in one of the spaces above (device px unless stated otherwise). */
data class ScreenRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(width > 0.0 && height > 0.0) { "width/height must be positive: $this" }
    }

    val right: Double get() = x + width
    val bottom: Double get() = y + height

    fun contains(point: ScreenPoint): Boolean =
        point.x >= x && point.x <= right && point.y >= y && point.y <= bottom

    fun toMap(): Map<String, Any?> =
        mapOf("x" to x, "y" to y, "width" to width, "height" to height)

    override fun toString(): String = "[%.0f, %.0f, %.0fx%.0f]".format(x, y, width, height)
}

/**
 * The browser window's outer frame, as reported by CDP `Browser.getWindowForTarget`
 * (Browser4 wraps it as `SessionWindowProbe.WindowBounds`). The values are DIP, i.e.
 * logical pixels of the monitor that hosts the window; [dpiScale] turns them into
 * physical pixels.
 *
 * @param dpiScale Windows display scale of the window's monitor: 1.0 = 100%, 1.5 = 150%.
 */
data class WindowFrame(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val dpiScale: Double = 1.0,
) {
    init {
        require(width > 0.0 && height > 0.0) { "window width/height must be positive: $this" }
        require(dpiScale > 0.0) { "dpiScale must be positive: $dpiScale" }
    }

    val physicalLeft: Double get() = left * dpiScale
    val physicalTop: Double get() = top * dpiScale
    val physicalWidth: Double get() = width * dpiScale
    val physicalHeight: Double get() = height * dpiScale

    fun toMap(): Map<String, Any?> = mapOf(
        "left" to left, "top" to top, "width" to width, "height" to height,
        "dpiScale" to dpiScale,
        "physical" to ScreenRect(physicalLeft, physicalTop, physicalWidth, physicalHeight).toMap(),
    )

    companion object {
        /**
         * Parses `"left,top,width,height"` or `"left,top,width,height,dpiScale"`.
         * Kept as a plain string so the tool surface stays small; JSON is accepted by the
         * HTTP/MCP layer if a client prefers it.
         */
        fun parse(spec: String): WindowFrame {
            val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.size == 4 || parts.size == 5) {
                "window frame must be 'left,top,width,height[,dpiScale]' | actual='$spec'"
            }
            return WindowFrame(
                left = parts[0].toDoubleOrNull() ?: throw IllegalArgumentException("bad left in '$spec'"),
                top = parts[1].toDoubleOrNull() ?: throw IllegalArgumentException("bad top in '$spec'"),
                width = parts[2].toDoubleOrNull() ?: throw IllegalArgumentException("bad width in '$spec'"),
                height = parts[3].toDoubleOrNull() ?: throw IllegalArgumentException("bad height in '$spec'"),
                dpiScale = if (parts.size == 5) {
                    parts[4].toDoubleOrNull() ?: throw IllegalArgumentException("bad dpiScale in '$spec'")
                } else 1.0,
            )
        }
    }
}

/**
 * What the page itself knows about its window, read with a single JS evaluation:
 *
 * ```js
 * ({
 *   innerWidth, innerHeight, outerWidth, outerHeight, devicePixelRatio,
 *   visualOffsetX: visualViewport?.offsetLeft ?? 0,
 *   visualOffsetY: visualViewport?.offsetTop ?? 0,
 * })
 * ```
 *
 * `devicePixelRatio` is the *only* factor needed to go from CSS px to device px: Chrome
 * folds both the OS display scale and the page zoom into it.
 */
data class ViewportMetrics(
    val innerWidth: Double,
    val innerHeight: Double,
    val outerWidth: Double,
    val outerHeight: Double,
    val devicePixelRatio: Double,
    val visualViewportOffsetX: Double = 0.0,
    val visualViewportOffsetY: Double = 0.0,
) {
    init {
        require(innerWidth > 0.0 && innerHeight > 0.0) { "innerWidth/innerHeight must be > 0" }
        require(outerWidth > 0.0 && outerHeight > 0.0) { "outerWidth/outerHeight must be > 0" }
        require(devicePixelRatio > 0.0) { "devicePixelRatio must be > 0: $devicePixelRatio" }
    }

    val viewportWidthDevice: Double get() = innerWidth * devicePixelRatio
    val viewportHeightDevice: Double get() = innerHeight * devicePixelRatio

    fun toMap(): Map<String, Any?> = mapOf(
        "innerWidth" to innerWidth, "innerHeight" to innerHeight,
        "outerWidth" to outerWidth, "outerHeight" to outerHeight,
        "devicePixelRatio" to devicePixelRatio,
        "visualViewportOffsetX" to visualViewportOffsetX,
        "visualViewportOffsetY" to visualViewportOffsetY,
    )

    companion object {
        /** Parses `"innerWidth,innerHeight,outerWidth,outerHeight,devicePixelRatio"`. */
        fun parse(spec: String): ViewportMetrics {
            val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.size == 5 || parts.size == 7) {
                "viewport metrics must be 'innerWidth,innerHeight,outerWidth,outerHeight,dpr[,visualOffsetX,visualOffsetY]'" +
                    " | actual='$spec'"
            }
            fun value(index: Int, name: String): Double =
                parts[index].toDoubleOrNull() ?: throw IllegalArgumentException("bad $name in '$spec'")

            return ViewportMetrics(
                innerWidth = value(0, "innerWidth"),
                innerHeight = value(1, "innerHeight"),
                outerWidth = value(2, "outerWidth"),
                outerHeight = value(3, "outerHeight"),
                devicePixelRatio = value(4, "devicePixelRatio"),
                visualViewportOffsetX = if (parts.size == 7) value(5, "visualOffsetX") else 0.0,
                visualViewportOffsetY = if (parts.size == 7) value(6, "visualOffsetY") else 0.0,
            )
        }
    }
}

/**
 * Distance, in device px, from the window's outer edge to the page viewport's edge.
 * Chrome does not expose it, so it is either configured, derived approximately, or
 * calibrated once (see `docs/viewport-to-screen.md`).
 */
data class ChromeInsets(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
) {
    fun toMap(): Map<String, Any?> =
        mapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)

    companion object {
        /** App mode / kiosk / full-bleed windows: the viewport starts at the window origin. */
        val EMPTY = ChromeInsets()

        /**
         * Approximate insets from window and viewport sizes alone.
         *
         * Horizontal chrome is assumed symmetric (true for the window borders, slightly off
         * when a classic scrollbar is visible). Vertical chrome is everything that is not
         * viewport, minus [assumedBottomInset] (0 normally, ~8 px when the window is
         * maximised on Windows 10/11, where the frame is clipped at the screen edge).
         */
        fun derived(
            frame: WindowFrame,
            metrics: ViewportMetrics,
            assumedBottomInset: Double = 0.0,
        ): ChromeInsets {
            val horizontal = ((frame.physicalWidth - metrics.viewportWidthDevice) / 2.0)
                .coerceAtLeast(0.0)
            val top = (frame.physicalHeight - metrics.viewportHeightDevice - assumedBottomInset)
                .coerceAtLeast(0.0)
            return ChromeInsets(
                left = horizontal,
                top = top,
                right = horizontal,
                bottom = assumedBottomInset.coerceAtLeast(0.0),
            )
        }

        /** Parses `"left,top,right,bottom"` (device px). */
        fun parse(spec: String): ChromeInsets {
            val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(parts.size == 4) { "insets must be 'left,top,right,bottom' | actual='$spec'" }
            fun value(index: Int): Double =
                parts[index].toDoubleOrNull() ?: throw IllegalArgumentException("bad inset[$index] in '$spec'")
            return ChromeInsets(value(0), value(1), value(2), value(3))
        }
    }
}
