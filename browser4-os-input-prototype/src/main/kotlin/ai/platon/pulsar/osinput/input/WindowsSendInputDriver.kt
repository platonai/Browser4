package ai.platon.pulsar.osinput.input

import ai.platon.pulsar.osinput.geometry.ScreenPoint
import ai.platon.pulsar.osinput.geometry.ScreenRect
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import kotlin.math.roundToInt

/**
 * Win32 `SendInput` backend built on the JDK's Foreign Function & Memory API (Java 22+),
 * so the plugin needs no JNA/native artifact.
 *
 * Advantages over [RobotPointerDriver]:
 *  - Exact **physical** pixels: absolute coordinates normalised across the whole virtual
 *    desktop (`MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK`), so mixed-DPI multi-monitor
 *    layouts behave like a human mouse.
 *  - Real `MOUSEINPUT` events, which is what games / canvas apps / anti-automation checks
 *    generally expect (Robot produces the same Win32 events, but its coordinates are AWT
 *    user space and it may insert extra cursor-position calls).
 *  - Room to grow into keyboard, wheel and `SendInput` batching.
 *
 * Limits (identical for any user-mode injector):
 *  - UIPI: an unelevated process cannot inject into an elevated window.
 *  - The input desktop must be the interactive one: locked workstation, disconnected RDP or
 *    a secure-desktop UAC prompt swallows the input.
 *  - JDK 24+ prints a native-access warning unless the launcher passes
 *    `--enable-native-access=ALL-UNNAMED`.
 */
class WindowsSendInputDriver : PointerDriver {

    override val backend: String = "win32.SendInput"

    private val arena: Arena = Arena.ofConfined()
    private val linker: Linker = Linker.nativeLinker()
    private val user32: SymbolLookup = SymbolLookup.libraryLookup("user32", arena)

    /**
     * `INPUT` for the mouse: `{ DWORD type; union { MOUSEINPUT mi; }; }`.
     * x64 layout: type@0, padding, mi@{dx@0, dy@4, mouseData@8, dwFlags@12, time@16,
     * padding, dwExtraInfo@24}; total 40 bytes.
     */
    private val inputLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.paddingLayout(4),
        MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("dx"),
            ValueLayout.JAVA_INT.withName("dy"),
            ValueLayout.JAVA_INT.withName("mouseData"),
            ValueLayout.JAVA_INT.withName("dwFlags"),
            ValueLayout.JAVA_INT.withName("time"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("dwExtraInfo"),
        ).withName("mouse"),
    )

    private val typeHandle = inputLayout.varHandle(PathElement.groupElement("type"))
    private val dxHandle = inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("dx"))
    private val dyHandle = inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("dy"))
    private val dataHandle =
        inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("mouseData"))
    private val flagsHandle =
        inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("dwFlags"))
    private val timeHandle = inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("time"))
    private val extraInfoHandle =
        inputLayout.varHandle(PathElement.groupElement("mouse"), PathElement.groupElement("dwExtraInfo"))

    private val sendInput: MethodHandle = linker.downcallHandle(
        user32.find("SendInput").orElseThrow { IllegalStateException("user32!SendInput not found") },
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )

    private val getSystemMetrics: MethodHandle = linker.downcallHandle(
        user32.find("GetSystemMetrics").orElseThrow { IllegalStateException("user32!GetSystemMetrics not found") },
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )

    /** Union of all monitors in physical pixels; origin may be negative. */
    override val virtualScreen: ScreenRect by lazy {
        val x = metric(SM_XVIRTUALSCREEN)
        val y = metric(SM_YVIRTUALSCREEN)
        val width = metric(SM_CXVIRTUALSCREEN)
        val height = metric(SM_CYVIRTUALSCREEN)
        ScreenRect(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }

    override fun moveTo(point: ScreenPoint) {
        val p = requireInsideVirtualScreen(point)
        sendMouse(flags = MOUSEEVENTF_MOVE or MOUSEEVENTF_ABSOLUTE or MOUSEEVENTF_VIRTUALDESK, point = p)
    }

    override fun click(point: ScreenPoint, button: MouseButton, doubleClick: Boolean) {
        val p = requireInsideVirtualScreen(point)
        moveTo(p)
        val (down, up) = buttonFlags(button)
        val absoluteFlags = MOUSEEVENTF_ABSOLUTE or MOUSEEVENTF_VIRTUALDESK
        sendMouse(down or absoluteFlags, p)
        sendMouse(up or absoluteFlags, p)
        if (doubleClick) {
            sendMouse(down or absoluteFlags, p)
            sendMouse(up or absoluteFlags, p)
        }
    }

    override fun close() {
        arena.close()
    }

    private fun sendMouse(flags: Int, point: ScreenPoint, data: Int = 0) {
        val segment = arena.allocate(inputLayout)
        typeHandle.set(segment, 0L, TYPE_MOUSE)
        dxHandle.set(segment, 0L, SendInputCoordinates.horizontal(point.x, virtualScreen))
        dyHandle.set(segment, 0L, SendInputCoordinates.vertical(point.y, virtualScreen))
        dataHandle.set(segment, 0L, data)
        flagsHandle.set(segment, 0L, flags)
        timeHandle.set(segment, 0L, 0)
        extraInfoHandle.set(segment, 0L, 0L)

        val inserted = sendInput.invokeWithArguments(1, segment, inputLayout.byteSize().toInt()) as Int
        check(inserted == 1) {
            "SendInput returned $inserted (expected 1); the input desktop may be blocked (UIPI, lock screen, RDP)"
        }
    }

    private fun metric(index: Int): Int = getSystemMetrics.invokeWithArguments(index) as Int

    private fun buttonFlags(button: MouseButton): Pair<Int, Int> = when (button) {
        MouseButton.LEFT -> MOUSEEVENTF_LEFTDOWN to MOUSEEVENTF_LEFTUP
        MouseButton.RIGHT -> MOUSEEVENTF_RIGHTDOWN to MOUSEEVENTF_RIGHTUP
        MouseButton.MIDDLE -> MOUSEEVENTF_MIDDLEDOWN to MOUSEEVENTF_MIDDLEUP
    }

    companion object {
        private const val TYPE_MOUSE = 0

        private const val MOUSEEVENTF_MOVE = 0x0001
        private const val MOUSEEVENTF_LEFTDOWN = 0x0002
        private const val MOUSEEVENTF_LEFTUP = 0x0004
        private const val MOUSEEVENTF_RIGHTDOWN = 0x0008
        private const val MOUSEEVENTF_RIGHTUP = 0x0010
        private const val MOUSEEVENTF_MIDDLEDOWN = 0x0020
        private const val MOUSEEVENTF_MIDDLEUP = 0x0040
        private const val MOUSEEVENTF_VIRTUALDESK = 0x4000
        private const val MOUSEEVENTF_ABSOLUTE = 0x8000

        private const val SM_XVIRTUALSCREEN = 76
        private const val SM_YVIRTUALSCREEN = 77
        private const val SM_CXVIRTUALSCREEN = 78
        private const val SM_CYVIRTUALSCREEN = 79

        fun isSupported(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
    }
}

/**
 * Win32 normalises absolute mouse coordinates to `0..65535` across the virtual desktop:
 *
 * ```
 * dx = round((x - virtualLeft) * 65535 / (virtualWidth - 1))
 * ```
 *
 * Extracted so it can be unit-tested on any OS.
 */
object SendInputCoordinates {
    const val MAX = 65535

    fun horizontal(x: Double, virtualScreen: ScreenRect): Int = normalize(x, virtualScreen.x, virtualScreen.width)

    fun vertical(y: Double, virtualScreen: ScreenRect): Int = normalize(y, virtualScreen.y, virtualScreen.height)

    fun normalize(value: Double, origin: Double, span: Double): Int {
        if (span <= 1.0) return 0
        val normalized = (value - origin) * MAX / (span - 1.0)
        return normalized.roundToInt().coerceIn(0, MAX)
    }
}
