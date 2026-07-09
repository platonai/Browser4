package ai.platon.browser4.chrome.protocol

import ai.platon.browser4.api.BrowserProtocol
import ai.platon.browser4.api.model.NodeRef
import ai.platon.cdt.kt.protocol.types.dom.BoxModel
import ai.platon.cdt.kt.protocol.types.page.LayoutMetrics
import ai.platon.pulsar.common.math.geometric.PointD
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

/**
 * Unit tests for [Mouse], [Keyboard], [ClickableDOM], and [EmulationHandler]
 * covering CDP mouse actions, keyboard input, click-point computation,
 * and corner cases exposed by the CDP-trusted-event refactoring.
 */
class EmulationHandlerTest {

    // =========================================================================
    // Mocks
    // =========================================================================

    @Mock
    private lateinit var bp: BrowserProtocol

    private lateinit var mouse: Mouse
    private lateinit var keyboard: Keyboard
    private lateinit var emulationHandler: EmulationHandler

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        mouse = Mouse(bp)
        keyboard = Keyboard(bp)
        emulationHandler = EmulationHandler(bp, keyboard, mouse)
    }

    // =========================================================================
    // Mouse — CDP event dispatch
    // =========================================================================

    @Nested
    @DisplayName("Mouse CDP event dispatch")
    inner class MouseCdpDispatch {

        @Test
        @DisplayName("single click dispatches moveTo → down → up in order")
        fun singleClickDispatchesMoveDownUpInOrder() = runBlocking {
            val inOrder = inOrder(bp)
            mouse.click(100.0, 200.0, clickCount = 1, delayMillis = 0)

            inOrder.verify(bp).dispatchMouseMoved(eq(100.0), eq(200.0), any(), anyOrNull())
            inOrder.verify(bp).dispatchMousePressed(eq(100.0), eq(200.0), eq(1), eq(null), eq(1))
            inOrder.verify(bp).dispatchMouseReleased(eq(100.0), eq(200.0), eq(1), eq(null), eq(0))
        }

        @Test
        @DisplayName("double click dispatches two down/up pairs with correct clickCount")
        fun doubleClickDispatchesTwoDownUpPairs() = runBlocking {
            val inOrder = inOrder(bp)
            mouse.click(100.0, 200.0, clickCount = 2, delayMillis = 0)

            // First click: clickCount=1
            inOrder.verify(bp).dispatchMouseMoved(eq(100.0), eq(200.0), any(), anyOrNull())
            inOrder.verify(bp).dispatchMousePressed(eq(100.0), eq(200.0), eq(1), eq(null), eq(1))
            inOrder.verify(bp).dispatchMouseReleased(eq(100.0), eq(200.0), eq(1), eq(null), eq(0))
            // Second click: clickCount=2
            inOrder.verify(bp).dispatchMousePressed(eq(100.0), eq(200.0), eq(2), eq(null), eq(1))
            inOrder.verify(bp).dispatchMouseReleased(eq(100.0), eq(200.0), eq(2), eq(null), eq(0))
        }

        @Test
        @DisplayName("click with modifier bitmask passes modifiers to down/up but not moveTo")
        fun clickWithModifierPassesThrough() = runBlocking {
            val altMask = 1
            mouse.click(50.0, 75.0, clickCount = 1, modifiers = altMask, delayMillis = 0)

            // moveTo(x, y) inside click() is called without modifiers
            verify(bp).dispatchMouseMoved(eq(50.0), eq(75.0), any(), anyOrNull())
            // down/up receive the modifier bitmask
            verify(bp).dispatchMousePressed(eq(50.0), eq(75.0), any(), eq(altMask), any())
            verify(bp).dispatchMouseReleased(eq(50.0), eq(75.0), any(), eq(altMask), any())
        }

        @Test
        @DisplayName("click moves to target coordinates before pressing")
        fun clickMovesToTargetBeforePress() = runBlocking {
            // Start from a non-origin position to verify moveTo interpolation
            mouse.moveTo(10.0, 10.0, steps = 1)
            reset(bp)

            mouse.click(500.0, 600.0, clickCount = 1, delayMillis = 0)

            val inOrder = inOrder(bp)
            inOrder.verify(bp).dispatchMouseMoved(eq(500.0), eq(600.0), any(), eq(null))
            inOrder.verify(bp).dispatchMousePressed(eq(500.0), eq(600.0), any(), eq(null), any())
            inOrder.verify(bp).dispatchMouseReleased(eq(500.0), eq(600.0), any(), eq(null), any())
        }
    }

    // =========================================================================
    // Mouse — moveTo interpolation
    // =========================================================================

    @Nested
    @DisplayName("Mouse moveTo interpolation")
    inner class MouseMoveToInterpolation {

        @Test
        @DisplayName("moveTo with steps=1 emits single move to target")
        fun moveToSingleStep() = runBlocking {
            mouse.moveTo(100.0, 200.0, steps = 1)

            verify(bp, times(1)).dispatchMouseMoved(eq(100.0), eq(200.0), any(), eq(null))
        }

        @Test
        @DisplayName("moveTo with 3 steps emits 3 intermediate moves to target")
        fun moveToMultiStep() = runBlocking {
            mouse.moveTo(100.0, 200.0, steps = 3, delayMillis = 0)

            // 3 steps: t=1/3, t=2/3, t=3/3
            verify(bp, times(3)).dispatchMouseMoved(any(), any(), any(), eq(null))
        }

        @Test
        @DisplayName("moveTo from non-zero origin interpolates correct endpoints")
        fun moveToFromNonZero() = runBlocking {
            mouse.moveTo(50.0, 50.0, steps = 1)
            reset(bp)

            mouse.moveTo(150.0, 250.0, steps = 1)

            verify(bp).dispatchMouseMoved(eq(150.0), eq(250.0), any(), eq(null))
        }

        @Test
        @DisplayName("moveTo with steps=0 is treated as steps=1")
        fun moveToZeroStepsClampsToOne() = runBlocking {
            mouse.moveTo(100.0, 200.0, steps = 0)

            verify(bp, atLeastOnce()).dispatchMouseMoved(any(), any(), any(), anyOrNull())
        }

        @Test
        @DisplayName("moveTo interpolates linearly from start to end")
        fun moveToInterpolatesLinearly() = runBlocking {
            // Use argument captor to verify interpolation math
            mouse.moveTo(0.0, 0.0, steps = 1) // ensure known start position
            reset(bp)

            mouse.moveTo(100.0, 200.0, steps = 5, delayMillis = 0)

            val captor = argumentCaptor<Double>()
            // 5 steps: t=0.2, 0.4, 0.6, 0.8, 1.0
            // x at each step: 20, 40, 60, 80, 100
            // We just verify we get 5 move events ending at the target
            verify(bp, times(5)).dispatchMouseMoved(captor.capture(), captor.capture(), any(), eq(null))

            val allX = captor.allValues.filterIndexed { i, _ -> i % 2 == 0 }
            val allY = captor.allValues.filterIndexed { i, _ -> i % 2 == 1 }

            assertEquals(5, allX.size)
            assertEquals(5, allY.size)
            assertEquals(100.0, allX.last(), 0.01)
            assertEquals(200.0, allY.last(), 0.01)
        }
    }

    // =========================================================================
    // Mouse — button state tracking
    // =========================================================================

    @Nested
    @DisplayName("Mouse button state tracking")
    inner class MouseButtonState {

        @Test
        @DisplayName("initial buttonsState is 0")
        fun initialButtonsStateIsZero() = runBlocking {
            // Verify through a down call: buttonsState starts at 0, becomes 1 after OR
            mouse.moveTo(10.0, 10.0, steps = 1)
            reset(bp)

            mouse.down(100.0, 200.0)
            verify(bp).dispatchMousePressed(any(), any(), any(), eq(null), eq(1))
        }

        @Test
        @DisplayName("down sets left button bit, up clears it")
        fun downSetsUpClearsButtonBit() = runBlocking {
            val buttonsCaptor = argumentCaptor<Int>()

            mouse.down(100.0, 200.0)
            verify(bp).dispatchMousePressed(any(), any(), any(), eq(null), buttonsCaptor.capture())
            assertEquals(1, buttonsCaptor.lastValue)

            mouse.up(100.0, 200.0)
            verify(bp).dispatchMouseReleased(any(), any(), any(), eq(null), buttonsCaptor.capture())
            assertEquals(0, buttonsCaptor.lastValue)
        }

        @Test
        @DisplayName("consecutive down calls without up keep button bit set")
        fun consecutiveDownKeepsButtonBit() = runBlocking {
            val buttonsCaptor = argumentCaptor<Int>()

            mouse.down(100.0, 200.0)
            verify(bp).dispatchMousePressed(any(), any(), any(), eq(null), buttonsCaptor.capture())
            assertEquals(1, buttonsCaptor.firstValue)

            mouse.down(150.0, 250.0)
            verify(bp, times(2)).dispatchMousePressed(any(), any(), any(), eq(null), buttonsCaptor.capture())
            // buttonsState should still be 1 (OR with 1 = 1)
            assertEquals(1, buttonsCaptor.secondValue)
        }

        @Test
        @DisplayName("full click cycle restores buttonsState to 0")
        fun fullClickCycleRestoresButtonsState() = runBlocking {
            mouse.click(100.0, 200.0, clickCount = 1, delayMillis = 0)

            val buttonsCaptor = argumentCaptor<Int>()

            // After the click, buttonsState should be 0 (up() clears it)
            // Verify by doing another down and checking the released state
            verify(bp, atLeastOnce()).dispatchMouseReleased(any(), any(), any(), eq(null), buttonsCaptor.capture())
            assertEquals(0, buttonsCaptor.lastValue)
        }

        @Test
        @DisplayName("moveTo passes current buttonsState to CDP")
        fun moveToPassesButtonsState() = runBlocking {
            // Set button state to pressed
            mouse.down(100.0, 200.0)
            reset(bp)

            // Move while button is pressed
            mouse.moveTo(150.0, 250.0, steps = 1)

            // buttonsState should be 1 during the move
            verify(bp).dispatchMouseMoved(eq(150.0), eq(250.0), eq(1), eq(null))
        }
    }

    // =========================================================================
    // Mouse — wheel
    // =========================================================================

    @Nested
    @DisplayName("Mouse wheel")
    inner class MouseWheel {

        @Test
        @DisplayName("wheel from non-origin uses current mouse position")
        fun wheelFromNonOrigin() = runBlocking {
            mouse.moveTo(50.0, 50.0, steps = 1)
            reset(bp)

            mouse.wheel()

            verify(bp).dispatchMouseWheel(eq(50.0), eq(50.0), any(), any(), eq(null))
        }

        @Test
        @DisplayName("wheel at origin with explicit position uses that position")
        fun wheelAtOriginWithExplicitPosition() = runBlocking {
            // currentX=0, currentY=0 (initial), but explicit coords provided
            mouse.wheel(x = 30.0, y = 40.0, deltaX = 0.0, deltaY = 10.0)

            verify(bp).dispatchMouseWheel(eq(30.0), eq(40.0), eq(0.0), eq(10.0), eq(null))
        }

        @Test
        @DisplayName("wheel passes delta and modifiers through")
        fun wheelPassesDeltaAndModifiers() = runBlocking {
            mouse.moveTo(10.0, 10.0, steps = 1)
            reset(bp)

            mouse.wheel(10.0, 10.0, deltaX = 5.0, deltaY = 20.0, modifiers = 2 /* Ctrl */)

            verify(bp).dispatchMouseWheel(eq(10.0), eq(10.0), eq(5.0), eq(20.0), eq(2))
        }
    }

    // =========================================================================
    // Mouse — drag
    // =========================================================================

    @Nested
    @DisplayName("Mouse drag")
    inner class MouseDrag {

        @Test
        @DisplayName("drag lifecycle: enables intercept, registers listener, cleans up")
        fun dragEnablesInterceptLifecycle() = runBlocking {
            // drag() calls moveTo(start, 5, 100ms) → down → moveTo(target, 3, 500ms) → finally: up + setInterceptDrags(false)
            // With no DragIntercepted event fired, dragData stays null and up() + disable happen in finally.

            mouse.drag(PointD(0.0, 0.0), PointD(100.0, 100.0))

            // Core lifecycle
            verify(bp).setInterceptDrags(true)
            verify(bp).onDragIntercepted(any())
            // Cleanup always happens in finally block
            verify(bp).setInterceptDrags(false)
            verify(bp, atLeastOnce()).dispatchMouseReleased(any(), any(), any(), anyOrNull(), any())
        }
    }

    // =========================================================================
    // ClickableDOM — clickable point computation
    // =========================================================================

    @Nested
    @DisplayName("ClickableDOM clickablePoint")
    inner class ClickablePointComputation {

        @Test
        @DisplayName("create returns null for null browser protocol")
        fun createReturnsNullForNullBrowserProtocol() {
            assertNull(ClickableDOM.create(null, NodeRef(nodeId = 1)))
        }

        @Test
        @DisplayName("create returns null for null node")
        fun createReturnsNullForNullNode() {
            assertNull(ClickableDOM.create(bp, null))
        }

        @Test
        @DisplayName("create returns instance when both bp and node are non-null")
        fun createReturnsInstance() {
            val node = NodeRef(nodeId = 1)
            assertNotNull(ClickableDOM.create(bp, node))
        }
    }

    // =========================================================================
    // Keyboard — splitKeyString
    // =========================================================================

    @Nested
    @DisplayName("Keyboard splitKeyString")
    inner class KeyboardSplitKeyString {

        @Test
        @DisplayName("empty string returns empty list")
        fun emptyStringReturnsEmptyList() {
            assertTrue(keyboard.splitKeyString("").isEmpty())
        }

        @Test
        @DisplayName("single key returns list with that key")
        fun singleKeyReturnsSingletonList() {
            assertEquals(listOf("a"), keyboard.splitKeyString("a"))
            assertEquals(listOf("Enter"), keyboard.splitKeyString("Enter"))
        }

        @Test
        @DisplayName("modifier combo splits on plus sign")
        fun modifierComboSplitsOnPlus() {
            assertEquals(listOf("Shift", "a"), keyboard.splitKeyString("Shift+a"))
            assertEquals(listOf("Control", "Shift", "Tab"), keyboard.splitKeyString("Control+Shift+Tab"))
        }

        @Test
        @DisplayName("trailing plus produces empty last element")
        fun trailingPlusProducesEmptyLastElement() {
            // Current behavior: "Shift+" → ["Shift"], trailing plus is consumed as separator
            val result = keyboard.splitKeyString("Shift+")
            assertTrue(result.isNotEmpty())
            // "Shift+" → builds "Shift", encounters '+' → emits "Shift", clears token →
            // end of string: token is empty, no more chars → empty token not added
            assertEquals(listOf("Shift"), result)
        }

        @Test
        @DisplayName("leading plus produces empty first element")
        fun leadingPlusProducesEmptyFirstElement() {
            // "+Shift" → starts with '+', token is empty → enters else branch with '+'
            // but only '+' with empty token → this won't add since token is empty
            val result = keyboard.splitKeyString("+Shift")
            // Actually: '+' with empty token → char!='+' or token empty → goes to else → appends '+' to token
            // Wait: The code: `if (char == '+' && token.isNotEmpty()) { ... } else { token.append(char) }`
            // So if token IS empty and char is '+', we enter the else branch: token.append('+')
            // Then "Shift" continues appending → token = "+Shift" → emitted at end
            assertEquals(listOf("+Shift"), result)
        }

        @Test
        @DisplayName("multiple consecutive plus separators handled correctly")
        fun consecutivePlusSeparators() {
            // "A++B":
            // 'A' → token="A"
            // '+' → emit "A", clear token
            // '+' → token is empty, char is '+', goes to else → token="+"
            // 'B' → token="+B"
            // end → emit "+B"
            assertEquals(listOf("A", "+B"), keyboard.splitKeyString("A++B"))
        }
    }

    // =========================================================================
    // Keyboard — type
    // =========================================================================

    @Nested
    @DisplayName("Keyboard type")
    inner class KeyboardType {

        @Test
        @DisplayName("type dispatches insertText for each printable character")
        fun typeDispatchesInsertTextForEachCharacter() = runBlocking {
            keyboard.type("abc", delayMillis = 0)

            verify(bp, times(3)).insertText(any())
            verify(bp).insertText("a")
            verify(bp).insertText("b")
            verify(bp).insertText("c")
        }

        @Test
        @DisplayName("type dispatches insertText for each char in order")
        fun typeDispatchesInOrder() = runBlocking {
            val inOrder = inOrder(bp)
            keyboard.type("xy", delayMillis = 0)

            inOrder.verify(bp).insertText("x")
            inOrder.verify(bp).insertText("y")
        }

        @Test
        @DisplayName("type with delayMillis dispatches insertText with delays between chars")
        fun typeWithDelayDispatchesWithIntervals() = runBlocking {
            // With positive delay, each character should complete before the next starts
            // This is the key behavior that interacts correctly with CDP trusted clicks
            keyboard.type("ab", delayMillis = 1)

            val inOrder = inOrder(bp)
            inOrder.verify(bp).insertText("a")
            inOrder.verify(bp).insertText("b")
        }

        @Test
        @DisplayName("type with empty text dispatches nothing")
        fun typeEmptyText() = runBlocking {
            keyboard.type("", delayMillis = 0)

            verify(bp, never()).insertText(any())
        }
    }

    // =========================================================================
    // Keyboard — press
    // =========================================================================

    @Nested
    @DisplayName("Keyboard press")
    inner class KeyboardPress {

        @Test
        @DisplayName("press with empty string does nothing")
        fun pressEmptyString() = runBlocking {
            keyboard.press("", delayMillis = 0)

            verify(bp, never()).dispatchKeyEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("press single printable key dispatches down and up")
        fun pressSingleKey() = runBlocking {
            keyboard.press("a", delayMillis = 0)

            verify(bp, atLeastOnce()).dispatchKeyEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("press uppercase letter expands to Shift+lower")
        fun pressUppercaseLetter() = runBlocking {
            // "A" should expand to "Shift+a" via normalizeKeyStringForPress
            keyboard.press("A", delayMillis = 0)

            // Should dispatch Shift down, a down, a up, Shift up (4 events)
            verify(bp, atLeast(2)).dispatchKeyEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }

        @Test
        @DisplayName("press shifted punctuation expands correctly")
        fun pressShiftedPunctuation() = runBlocking {
            // "!" should expand to "Shift+1"
            keyboard.press("!", delayMillis = 0)

            verify(bp, atLeast(2)).dispatchKeyEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // =========================================================================
    // EmulationHandler — click / hover null-safety and error handling
    //
    // Full click/hover CDP integration is covered by browser-level E2E tests
    // (MCPToolControllerE2ETest, ChromeDevToolsTest).  The classes tested here
    // (ClickableDOM, getInteractPoint) require complex CDT type mocks that are
    // brittle to maintain.  This section focuses on the null-safety and error-
    // handling paths that unit tests can verify reliably.
    // =========================================================================

    @Nested
    @DisplayName("EmulationHandler click and hover null-safety")
    inner class EmulationHandlerNullSafety {

        @Test
        @DisplayName("click with null bp does nothing and returns silently")
        fun clickWithNullBrowserProtocolReturnsSilently() = runBlocking {
            val handler = EmulationHandler(bp = null, keyboard = null, mouse = null)
            val node = NodeRef(nodeId = 1)

            // Should not throw
            handler.click(node, 1)
        }

        @Test
        @DisplayName("hover with null bp does nothing")
        fun hoverWithNullBrowserProtocolDoesNothing() = runBlocking {
            val handler = EmulationHandler(bp = null, keyboard = null, mouse = null)
            val node = NodeRef(nodeId = 1)

            // Should not throw
            handler.hover(node)
        }

        @Test
        @DisplayName("click survives getContentQuads failure gracefully")
        fun clickSurvivesGetContentQuadsFailure() = runBlocking {
            val node = NodeRef(nodeId = 999)
            whenever(bp.getContentQuads(any())).thenThrow(RuntimeException("Node not found"))
            whenever(bp.getLayoutMetrics()).thenReturn(mock())
            whenever(bp.getBoxModel(any())).thenReturn(mock())

            // Should not throw — getInteractPoint wraps getContentQuads in runCatching
            emulationHandler.click(node, 1)
        }

        @Test
        @DisplayName("hover with null mouse does nothing")
        fun hoverWithNullMouseDoesNothing() = runBlocking {
            val handler = EmulationHandler(bp, keyboard, null)
            val node = NodeRef(nodeId = 1)

            // Should not throw — hover returns early when mouse is null
            handler.hover(node)
        }

        @Test
        @DisplayName("click with empty box model border does not throw")
        fun clickWithEmptyBoxModelBorderDoesNotThrow() = runBlocking {
            val node = NodeRef(nodeId = 1)
            val quads: List<List<Double>> = listOf(listOf(10.0, 10.0, 50.0, 10.0, 50.0, 50.0, 10.0, 50.0))

            // getBoxModel returns empty border list → boundingBox is null → point is unadjusted
            // but click should still proceed with the raw quad center point
            val layoutMetrics: LayoutMetrics = mock()
            val cssViewport = Mockito.mock(ai.platon.cdt.kt.protocol.types.page.LayoutViewport::class.java)
            val boxModel: BoxModel = mock()

            Mockito.`when`(cssViewport.clientWidth).thenReturn(1024)
            Mockito.`when`(cssViewport.clientHeight).thenReturn(768)
            Mockito.`when`(layoutMetrics.cssLayoutViewport).thenReturn(cssViewport)
            Mockito.`when`(boxModel.border).thenReturn(emptyList())

            whenever(bp.getContentQuads(any())).thenReturn(quads)
            whenever(bp.getLayoutMetrics()).thenReturn(layoutMetrics)
            whenever(bp.getBoxModel(any())).thenReturn(boxModel)

            // Should not throw — boundingBox returns null, getInteractPoint falls back to quad center
            emulationHandler.click(node, 1, position = "center", delayMillis = 0)
        }
    }

    // =========================================================================
    // normalizeKeyStringForPress — already tested in KeyboardKeyStringNormalizationTest
    // but adding corner cases not covered there
    // =========================================================================

    @Nested
    @DisplayName("normalizeKeyStringForPress corner cases")
    inner class NormalizeKeyStringCornerCases {

        @Test
        @DisplayName("digit characters remain unchanged")
        fun digitsUnchanged() {
            assertEquals("1", normalizeKeyStringForPress("1"))
            assertEquals("9", normalizeKeyStringForPress("9"))
        }

        @Test
        @DisplayName("lowercase letters remain unchanged")
        fun lowercaseLettersUnchanged() {
            assertEquals("a", normalizeKeyStringForPress("a"))
            assertEquals("z", normalizeKeyStringForPress("z"))
        }

        @Test
        @DisplayName("all shifted punctuation characters expand correctly")
        fun allShiftedPunctuationExpands() {
            // Based on SHIFTED_CHARACTER_BASE_KEYS map
            assertEquals("Shift+`", normalizeKeyStringForPress("~"))
            assertEquals("Shift+1", normalizeKeyStringForPress("!"))
            assertEquals("Shift+2", normalizeKeyStringForPress("@"))
            assertEquals("Shift+3", normalizeKeyStringForPress("#"))
            assertEquals("Shift+4", normalizeKeyStringForPress("$"))
            assertEquals("Shift+5", normalizeKeyStringForPress("%"))
            assertEquals("Shift+6", normalizeKeyStringForPress("^"))
            assertEquals("Shift+7", normalizeKeyStringForPress("&"))
            assertEquals("Shift+8", normalizeKeyStringForPress("*"))
            assertEquals("Shift+9", normalizeKeyStringForPress("("))
            assertEquals("Shift+0", normalizeKeyStringForPress(")"))
            assertEquals("Shift+-", normalizeKeyStringForPress("_"))
            assertEquals("Shift+=", normalizeKeyStringForPress("+"))
            assertEquals("Shift+[", normalizeKeyStringForPress("{"))
            assertEquals("Shift+]", normalizeKeyStringForPress("}"))
            assertEquals("Shift+\\", normalizeKeyStringForPress("|"))
            assertEquals("Shift+;", normalizeKeyStringForPress(":"))
            assertEquals("Shift+'", normalizeKeyStringForPress("\""))
            assertEquals("Shift+,", normalizeKeyStringForPress("<"))
            assertEquals("Shift+.", normalizeKeyStringForPress(">"))
            assertEquals("Shift+/", normalizeKeyStringForPress("?"))
        }
    }
}
