package ai.platon.pulsar.osinput.input

import ai.platon.pulsar.osinput.geometry.ScreenPoint
import ai.platon.pulsar.osinput.geometry.ScreenRect

/**
 * A real desktop pointer, driven from outside the browser.
 *
 * All coordinates are **device px** (physical desktop pixels) unless a driver's KDoc says
 * otherwise; [RobotPointerDriver] converts them to AWT user space internally, so callers
 * never have to know which backend is active.
 *
 * Implementations must be idempotent w.r.t. closing and must reject coordinates that fall
 * outside [virtualScreen] instead of silently clicking somewhere else.
 */
interface PointerDriver : AutoCloseable {

    /** Human-readable backend name, e.g. `win32.SendInput` or `java.awt.Robot`. */
    val backend: String

    /** The union of all monitors, in device px; may have negative origin on multi-monitor. */
    val virtualScreen: ScreenRect

    /** Moves the OS cursor. Does not press any button. */
    fun moveTo(point: ScreenPoint)

    /**
     * Moves the cursor to [point] and clicks.
     *
     * @param doubleClick two down/up pairs with a short pause, using the system double-click
     *   interval as an upper bound.
     */
    fun click(point: ScreenPoint, button: MouseButton = MouseButton.LEFT, doubleClick: Boolean = false)

    override fun close() {}

    /** Fail fast instead of clicking a random desktop location. */
    fun requireInsideVirtualScreen(point: ScreenPoint): ScreenPoint {
        val p = point.rounded()
        require(virtualScreen.contains(p)) {
            "screen point $p is outside the virtual desktop $virtualScreen"
        }
        return p
    }
}

enum class MouseButton {
    LEFT, RIGHT, MIDDLE;

    companion object {
        fun parse(value: String?): MouseButton = when (value?.trim()?.lowercase()) {
            null, "", "left", "l" -> LEFT
            "right", "r" -> RIGHT
            "middle", "m" -> MIDDLE
            else -> throw IllegalArgumentException("unknown mouse button '$value' (left|right|middle)")
        }
    }
}
