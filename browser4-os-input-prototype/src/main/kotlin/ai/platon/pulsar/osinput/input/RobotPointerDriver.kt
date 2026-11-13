package ai.platon.pulsar.osinput.input

import ai.platon.pulsar.osinput.geometry.ScreenPoint
import ai.platon.pulsar.osinput.geometry.ScreenRect
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.math.roundToInt

/**
 * `java.awt.Robot` backend: zero dependencies, works on any desktop JDK, and moves the real
 * OS cursor because AWT calls into the platform (Win32 `SetCursorPos` + `mouse_event`).
 *
 * Caveats it cannot hide:
 *  - Robot only works in an interactive desktop session. A Windows service, an RDP session
 *    that is disconnected, or a locked workstation makes it throw or move nothing.
 *  - AWT coordinates are the JDK's *user space*: on a 150% display `Robot.mouseMove(100, 100)`
 *    lands on device pixel (150, 150). This driver converts device px -> user space with the
 *    monitor transform, but the JDK's multi-monitor user space is anchored on the primary
 *    display, so mixed-DPI multi-monitor setups stay approximate. Prefer
 *    [WindowsSendInputDriver] when exact physical pixels matter.
 *  - UIPI: an unelevated process cannot send input to an elevated window.
 */
class RobotPointerDriver(
    /** AWT user space -> device px factor; derived from the primary screen by default. */
    private val awtScale: Double = AwtScreens.primaryScale(),
) : PointerDriver {

    override val backend: String = "java.awt.Robot"

    private val robot: Robot = createRobot()

    override val virtualScreen: ScreenRect = awtVirtualScreen(awtScale)

    override fun moveTo(point: ScreenPoint) {
        val awt = toAwt(requireInsideVirtualScreen(point))
        robot.mouseMove(awt.x, awt.y)
    }

    override fun click(point: ScreenPoint, button: MouseButton, doubleClick: Boolean) {
        moveTo(point)
        val mask = when (button) {
            MouseButton.LEFT -> InputEvent.BUTTON1_DOWN_MASK
            MouseButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
            MouseButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
        }
        robot.mousePress(mask)
        robot.mouseRelease(mask)
        if (doubleClick) {
            robot.delay(DOUBLE_CLICK_GAP_MILLIS)
            robot.mousePress(mask)
            robot.mouseRelease(mask)
        }
    }

    /** Alt+F4, Ctrl+W, ... — handy for "click then close the popup" scenarios. */
    fun keyStroke(keyCode: Int, vararg modifiers: Int) {
        modifiers.forEach { robot.keyPress(it) }
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        modifiers.reversed().forEach { robot.keyRelease(it) }
    }

    /** Literal text into the focused window (ASCII only; use the clipboard for the rest). */
    fun typeText(text: String) {
        text.forEach { ch -> typeKey(ch) }
    }

    /** Press and hold, e.g. for drags: call [releaseLeft] afterwards. */
    fun pressLeft() {
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    }

    fun releaseLeft() {
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    fun pressKey(keyCode: Int) {
        robot.keyPress(keyCode)
    }

    fun releaseKey(keyCode: Int) {
        robot.keyRelease(keyCode)
    }

    fun closePopupKey() {
        keyStroke(KeyEvent.VK_ESCAPE)
    }

    override fun close() {
        // java.awt.Robot has no close(); the native resources are released with the JVM.
    }

    private fun toAwt(point: ScreenPoint): java.awt.Point =
        java.awt.Point((point.x / awtScale).roundToInt(), (point.y / awtScale).roundToInt())

    private fun typeKey(ch: Char) {
        val key = KeyEvent.getExtendedKeyCodeForChar(ch.code)
        if (key == KeyEvent.VK_UNDEFINED) {
            throw IllegalArgumentException("cannot type character '$ch' through Robot key codes")
        }
        val needsShift = ch.isUpperCase() || ch in "~!@#$%^&*()_+{}|:\"<>?"
        if (needsShift) robot.keyPress(KeyEvent.VK_SHIFT)
        robot.keyPress(key)
        robot.keyRelease(key)
        if (needsShift) robot.keyRelease(KeyEvent.VK_SHIFT)
    }

    companion object {
        private const val DOUBLE_CLICK_GAP_MILLIS = 40

        fun isSupported(): Boolean = !GraphicsEnvironment.isHeadless()

        private fun createRobot(): Robot {
            check(isSupported()) {
                "java.awt.Robot needs a non-headless desktop session (GraphicsEnvironment.isHeadless() == true)"
            }
            return Robot().apply {
                isAutoWaitForIdle = false
                autoDelay = 0
            }
        }

        private fun awtVirtualScreen(scale: Double): ScreenRect {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val devices = env.screenDevices
            if (devices.isEmpty()) {
                throw IllegalStateException("no screen devices reported by AWT")
            }
            val bounds = devices.map { it.defaultConfiguration.bounds }
            val minX = bounds.minOf { it.x }
            val minY = bounds.minOf { it.y }
            val maxX = bounds.maxOf { it.x + it.width }
            val maxY = bounds.maxOf { it.y + it.height }
            return ScreenRect(
                minX * scale,
                minY * scale,
                (maxX - minX).toDouble() * scale,
                (maxY - minY).toDouble() * scale,
            )
        }
    }
}

/** Small helper for the AWT side of the mapping. */
object AwtScreens {
    /** AWT user space -> device px factor of the primary screen (1.0 on a 100% display). */
    fun primaryScale(): Double = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
    }.getOrDefault(1.0).takeIf { it > 0.0 } ?: 1.0
}
