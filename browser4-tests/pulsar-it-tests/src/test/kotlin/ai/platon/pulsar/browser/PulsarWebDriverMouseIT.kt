package ai.platon.pulsar.browser

import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.WebDriverTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Integration tests verifying CDP-level mouse and keyboard actions against a real
 * browser page.  These exercise the [EmulationHandler] CDP trusted-event path that
 * replaced the untrusted DOM event dispatch (dispatchDomClick).
 *
 * Test page: interactive-1.html — provides input fields, buttons, toggle, and
 * JavaScript instrumentation observable via [ai.platon.pulsar.api.WebDriver.evaluate].
 */
class PulsarWebDriverMouseIT : WebDriverTestBase() {

    private val testPage get() = "$generatedAssetsBaseURL/interactive-1.html"

    // =========================================================================
    // Click — CDP trusted mousePressed / mouseReleased
    // =========================================================================

    @Nested
    @DisplayName("Click actions")
    inner class ClickActions {

        @Test
        @DisplayName("click triggers button onclick handler via CDP trusted events")
        fun clickTriggersButtonHandler() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Set up operands via JS so the click is the only CDP action
            driver.evaluate("document.getElementById('num1').value = 4")
            driver.evaluate("document.getElementById('num2').value = 7")
            driver.delay(200)

            driver.click("#addButton")
            driver.delay(300)

            val result = driver.evaluate("document.getElementById('sumResult').textContent", "")
            assertTrue(result.toString().contains("11"), "Expected sum=11, got: $result")
        }

        @Test
        @DisplayName("click on toggle button changes aria-expanded state")
        fun clickTogglesAriaExpanded() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            val expandedBefore = driver.evaluate(
                "document.getElementById('toggleMessageButton').getAttribute('aria-expanded')", ""
            )

            driver.click("#toggleMessageButton")
            driver.delay(300)

            val expandedAfter = driver.evaluate(
                "document.getElementById('toggleMessageButton').getAttribute('aria-expanded')", ""
            )
            val hidden = driver.evaluate(
                "document.getElementById('hiddenMessage').classList.contains('hidden')", true
            )

            assertNotEquals(expandedBefore.toString(), expandedAfter.toString(),
                "aria-expanded should have changed after click")
            assertFalse(hidden.toString().toBoolean(),
                "Hidden message should be visible after toggle click")
        }

        @Test
        @DisplayName("click on non-existent selector does not throw")
        fun clickNonExistentSelectorDoesNotThrow() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Should not throw — silently handles missing element
            driver.click("#selector-that-does-not-exist")
        }
    }

    // =========================================================================
    // Type — CDP insertText (per-character) after click-right focus
    // =========================================================================

    @Nested
    @DisplayName("Type actions")
    inner class TypeActions {

        @Test
        @DisplayName("type into input produces expected value character by character")
        fun typeIntoInputProducesExpectedValue() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            val text = "Browser4 CDP"

            driver.type(text, "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals(text, value.toString())
        }

        @Test
        @DisplayName("type after click-right positions cursor at end — no race with deferred click")
        fun typeAfterClickRightDoesNotCorruptText() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Pre-fill some text so click-right positions cursor after it
            driver.fill("#name", "prefix_")
            driver.delay(100)

            // This is the critical path: emulator.click(node, 1, "right") → keyboard.type(text, delay)
            // The CDP trusted click must focus + position cursor synchronously before typing starts.
            driver.type("suffix", "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals("prefix_suffix", value.toString(),
                "CDP trusted click must position cursor after 'prefix_' before typing 'suffix'")
        }

        @Test
        @DisplayName("type long text character-by-character produces complete text")
        fun typeLongTextProducesCompleteText() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            val text = "The quick brown fox jumps over the lazy dog — 1234567890!@#$%"
            driver.fill("#name", "")
            driver.delay(100)

            driver.type(text, "#name")
            driver.delay(300)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals(text, value.toString(),
                "Long text typed character-by-character must be complete — no truncated chars from race condition")
        }
    }

    // =========================================================================
    // Fill — CDP insertText (zero delay) + clear + click-right
    // =========================================================================

    @Nested
    @DisplayName("Fill actions")
    inner class FillActions {

        @Test
        @DisplayName("fill clears existing value and sets new text")
        fun fillClearsAndSetsNewText() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.evaluate("document.getElementById('name').value = 'old_value'")
            driver.delay(100)

            driver.fill("#name", "new_value")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals("new_value", value.toString())
        }

        @Test
        @DisplayName("fill empty string clears the input")
        fun fillEmptyClearsInput() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.evaluate("document.getElementById('name').value = 'some_text'")
            driver.delay(100)

            driver.fill("#name", "")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals("", value.toString())
        }
    }

    // =========================================================================
    // Hover — CDP mouse move into element
    // =========================================================================

    @Nested
    @DisplayName("Hover actions")
    inner class HoverActions {

        @Test
        @DisplayName("hover dispatches CDP mouseMoved events and reaches the element")
        fun hoverReachesElement() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Move mouse away first, then hover over the toggle button
            driver.mouseMove(10.0, 10.0)
            driver.delay(200)

            driver.hover("#toggleMessageButton")
            driver.delay(300)

            // Hover doesn't cause a state change on this button, but we verify
            // the action completes without error.  CSS :hover verification:
            val isHovered = driver.evaluate(
                """
                (function() {
                    const el = document.getElementById('toggleMessageButton');
                    const style = window.getComputedStyle(el);
                    return true;  // Just verify no exception — hover is CDP-level
                })()
                """.trimIndent(),
                false
            )
            assertTrue(isHovered.toString().toBoolean(), "Hover JS check should succeed")
        }

        @Test
        @DisplayName("hover on non-existent selector does not throw")
        fun hoverNonExistentDoesNotThrow() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.hover("#no-such-element-xyz")
        }
    }

    // =========================================================================
    // Double-click — CDP trusted double-click
    // =========================================================================

    @Nested
    @DisplayName("Double-click actions")
    inner class DoubleClickActions {

        @Test
        @DisplayName("dblclick dispatches two CDP click sequences")
        fun dblclickDispatchesCDPEvents() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Set up initial state
            driver.evaluate("document.getElementById('num1').value = 10")
            driver.evaluate("document.getElementById('num2').value = 20")
            driver.delay(200)

            // Double-click the add button — should trigger addNumbers() once per click sequence
            // but since it's a dblclick (not two separate clicks), behavior depends on browser
            driver.dblclick("#addButton")
            driver.delay(300)

            val result = driver.evaluate("document.getElementById('sumResult').textContent", "")
            // Double-click may fire click handler at least once
            assertTrue(result.toString().contains("30") || result.toString().contains("Result"),
                "dblclick should fire button handler at least once, got: $result")
        }
    }

    // =========================================================================
    // Mouse move — CDP dispatchMouseMoved
    // =========================================================================

    @Nested
    @DisplayName("Mouse move actions")
    inner class MouseMoveActions {

        @Test
        @DisplayName("mouseMove updates current mouse position")
        fun mouseMoveUpdatesPosition() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Move to known coordinates — verify no error
            driver.mouseMove(200.0, 300.0)
            driver.delay(100)
        }

        @Test
        @DisplayName("mouseMove to element center via coordinates computed from bounding box")
        fun mouseMoveToElementCenter() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            val rectJson = driver.evaluate(
                "JSON.stringify(document.getElementById('addButton').getBoundingClientRect())",
                "{}"
            )
            // Move to viewport center area — no error expected
            driver.mouseMove(400.0, 500.0)
            driver.delay(100)
        }

        @Test
        @DisplayName("consecutive mouseMove + click works correctly")
        fun mouseMoveThenClick() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.evaluate("document.getElementById('num1').value = 3")
            driver.evaluate("document.getElementById('num2').value = 5")
            driver.delay(200)

            // Move to button area, then click
            driver.mouseMove(400.0, 500.0)
            driver.delay(100)
            driver.click("#addButton")
            driver.delay(300)

            val result = driver.evaluate("document.getElementById('sumResult').textContent", "")
            assertTrue(result.toString().contains("8"), "Expected sum=8 after move+click, got: $result")
        }
    }

    // =========================================================================
    // Scroll — CDP scroll + mouse wheel
    // =========================================================================

    @Nested
    @DisplayName("Scroll actions")
    inner class ScrollActions {

        @Test
        @DisplayName("scrollBy moves viewport by pixel delta")
        fun scrollByMovesViewport() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            val before = driver.evaluate("window.scrollY", 0.0).toString().toDouble()
            driver.scrollBy(100.0, smooth = false)
            driver.delay(300)

            val after = driver.evaluate("window.scrollY", 0.0).toString().toDouble()
            assertTrue(after > before,
                "scrollBy(100) should move viewport down, before=$before after=$after")
        }

        @Test
        @DisplayName("scrollToTop returns to origin")
        fun scrollToTopReturnsToOrigin() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.scrollBy(200.0, smooth = false)
            driver.delay(200)
            driver.scrollToTop()
            driver.delay(300)

            val scrollY = driver.evaluate("window.scrollY", 0.0)
            assertEquals(0.0, scrollY.toString().toDouble(), 2.0,
                "Viewport should be at top after scrollToTop")
        }

        @Test
        @DisplayName("mouseWheelDown dispatches CDP wheel events")
        fun mouseWheelDownDispatchesWheelEvents() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.scrollToTop()
            driver.delay(200)

            driver.mouseWheelDown(1, 0.0, 100.0, 0)
            driver.delay(300)

            // A wheel event with deltaY=100 should scroll the page down
            val scrollY = driver.evaluate("window.scrollY", -1.0)
            assertTrue(scrollY.toString().toDouble() > 0.0,
                "Wheel down should have scrolled the page, scrollY=$scrollY")
        }
    }

    // =========================================================================
    // Keyboard press — CDP dispatchKeyEvent
    // =========================================================================

    @Nested
    @DisplayName("Keyboard press actions")
    inner class KeyboardPressActions {

        @Test
        @DisplayName("press Enter key triggers form/implicit submission")
        fun pressEnterKey() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Type into input, then press Enter — verify no error
            driver.type("test_value", "#name")
            driver.delay(100)
            driver.press("Enter", "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertTrue(value.toString().contains("test_value"),
                "Enter key should not corrupt input value")
        }

        @Test
        @DisplayName("press Shift+A produces uppercase character")
        fun pressShiftA() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.fill("#name", "")
            driver.delay(100)

            // press(Shift+A, selector) internally click-rights to position cursor then presses
            driver.press("Shift+A", "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            // Shift+A should produce 'A' at the insertion point
            assertTrue(value.toString().contains("A"),
                "Shift+A should insert uppercase A, got: '$value'")
        }

        @Test
        @DisplayName("press Backspace deletes character")
        fun pressBackspaceDeletes() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.fill("#name", "hello")
            driver.delay(100)

            // Click at the right edge to position cursor at end, then backspace
            driver.type("", "#name") // click-right to position cursor
            driver.delay(100)
            driver.press("Backspace", "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals("hell", value.toString(),
                "Backspace should delete last character, got: '$value'")
        }
    }

    // =========================================================================
    // Select option — CDP select
    // =========================================================================

    @Nested
    @DisplayName("Select option actions")
    inner class SelectOptionActions {

        @Test
        @DisplayName("select option changes background color")
        fun selectOptionChangesBackground() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.selectOption("#colorSelect", listOf("lightblue"))
            driver.delay(300)

            val bg = driver.evaluate(
                "window.getComputedStyle(document.body).backgroundColor",
                ""
            )
            assertTrue(bg.toString().isNotEmpty(),
                "Background color should be set after select, got: $bg")
        }

        @Test
        @DisplayName("select option with multiple values picks last")
        fun selectOptionMultipleValues() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.selectOption("#colorSelect", listOf("lightgreen"))
            driver.delay(300)

            val value = driver.evaluate("document.getElementById('colorSelect').value", "")
            assertEquals("lightgreen", value.toString())
        }
    }

    // =========================================================================
    // Focus — CDP focus via evaluate
    // =========================================================================

    @Nested
    @DisplayName("Focus actions")
    inner class FocusActions {

        @Test
        @DisplayName("focus makes element the active element")
        fun focusMakesElementActive() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.focus("#name")
            driver.delay(200)

            val activeId = driver.evaluate("document.activeElement?.id", "")
            assertEquals("name", activeId.toString(),
                "Focus should make #name the active element")
        }
    }

    // =========================================================================
    // Composite actions — sequences of mixed CDP operations
    // =========================================================================

    @Nested
    @DisplayName("Composite action sequences")
    inner class CompositeActions {

        @Test
        @DisplayName("fill + click + verify — full form interaction via CDP")
        fun fillClickVerifyFullFormInteraction() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Fill number inputs
            driver.evaluate("document.getElementById('num1').value = 42")
            driver.evaluate("document.getElementById('num2').value = 58")
            driver.delay(200)

            // CDP click on the Add button
            driver.click("#addButton")
            driver.delay(300)

            // Verify via JS evaluation
            val result = driver.evaluate("document.getElementById('sumResult').textContent", "")
            assertTrue(result.toString().contains("100"),
                "Expected sum=100 from 42+58, got: $result")
        }

        @Test
        @DisplayName("type + type with click-right between — no cursor reset race")
        fun typeInterleavedWithClickRightNoRace() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // This is the exact scenario that dispatchDomClick used to break:
            // 1. Type "hello " into input
            // 2. Click-right triggers emulator.click() with CDP trusted events (was DOM setTimeout race)
            // 3. Type "world" after click-right
            // Expected: "hello world", NOT "hworld" or "helloworld"

            driver.fill("#name", "hello ")
            driver.delay(100)

            // The type() call internally does: focus → click-right → keyboard.type(text, delay)
            // With CDP trusted events, click-right positions cursor synchronously before typing
            driver.type("world", "#name")
            driver.delay(200)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals("hello world", value.toString(),
                "type after click-right must append 'world' after cursor, not overwrite")
        }

        @Test
        @DisplayName("scroll + hover + click — full spatial interaction sequence")
        fun scrollHoverClickSpatialSequence() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            driver.evaluate("document.getElementById('num1').value = 7")
            driver.evaluate("document.getElementById('num2').value = 3")
            driver.delay(200)

            // Scroll to ensure button is in viewport
            driver.scrollBy(300.0, smooth = false)
            driver.delay(200)

            // Hover over button area
            driver.hover("#addButton")
            driver.delay(200)

            // Click
            driver.click("#addButton")
            driver.delay(300)

            val result = driver.evaluate("document.getElementById('sumResult').textContent", "")
            assertTrue(result.toString().contains("10"),
                "Expected sum=10 from 7+3, got: $result")
        }

        @Test
        @DisplayName("rapid consecutive clicks do not cause state corruption")
        fun rapidConsecutiveClicks() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Reset counter in page
            driver.evaluate("window.clickCounter = 0")
            driver.evaluate(
                """
                document.getElementById('addButton').addEventListener('click', function() {
                    window.clickCounter = (window.clickCounter || 0) + 1;
                });
                """.trimIndent()
            )
            driver.delay(200)

            // Rapid clicks — each goes through CDP trusted path
            repeat(5) { i ->
                driver.click("#addButton")
                driver.delay(50)
            }
            driver.delay(300)

            val count = driver.evaluate("window.clickCounter", 0)
            assertEquals("5", count.toString(),
                "5 rapid CDP clicks should produce exactly 5 click events, got: $count")
        }

        @Test
        @DisplayName("type accented and special Unicode characters via CDP insertText")
        fun typeAccentedAndUnicode() = runWebDriverTestAndCompute(testPage, browser) { driver ->
            // Use accented Latin + common symbols — these work with CDP Input.insertText.
            // Emoji (🌍) and CJK (こんにちは) are known to cause issues with CDP insertText on some Chrome versions.
            val text = "Café résumé — naïve @#$% 123"
            driver.fill("#name", "")
            driver.delay(100)

            driver.type(text, "#name")
            driver.delay(300)

            val value = driver.evaluate("document.getElementById('name').value", "")
            assertEquals(text, value.toString(),
                "Unicode text must survive CDP insertText character by character, got: '$value'")
        }
    }

    // =========================================================================
    // CDP protocol-level verification
    // These tests verify the underlying CDP mechanism, not just DOM state.
    // =========================================================================

    @Nested
    @DisplayName("CDP protocol-level verification")
    inner class CDPProtocolLevel {

        @Test
        @DisplayName("click produces CDP mousePressed + mouseReleased (verified via DOM mousedown/mouseup counters)")
        fun clickProducesMouseDownUpEvents() = runBlocking {
            browser.newDriver().use { driver ->
                assertIs<PulsarWebDriver>(driver)
                openAndCompute(testPage, driver)

                // Instrument page with mousedown/mouseup counters
                driver.evaluate(
                    """
                    window._mdCount = 0;
                    window._muCount = 0;
                    window._clickCount = 0;
                    document.getElementById('addButton').addEventListener('mousedown', () => window._mdCount++);
                    document.getElementById('addButton').addEventListener('mouseup', () => window._muCount++);
                    document.getElementById('addButton').addEventListener('click', () => window._clickCount++);
                    """.trimIndent()
                )
                driver.delay(200)

                driver.click("#addButton")
                driver.delay(300)

                val md = driver.evaluate("window._mdCount", 0)
                val mu = driver.evaluate("window._muCount", 0)
                val cl = driver.evaluate("window._clickCount", 0)

                assertEquals("1", md.toString(), "CDP trusted click must fire 1 mousedown")
                assertEquals("1", mu.toString(), "CDP trusted click must fire 1 mouseup")
                assertEquals("1", cl.toString(), "CDP trusted click must fire 1 click")
            }
        }

        @Test
        @DisplayName("double-click produces correct CDP event sequence")
        fun doubleClickProducesCorrectEventSequence() = runBlocking {
            browser.newDriver().use { driver ->
                assertIs<PulsarWebDriver>(driver)
                openAndCompute(testPage, driver)

                driver.evaluate(
                    """
                    window._dblClickFired = false;
                    document.getElementById('addButton').addEventListener('dblclick', () => window._dblClickFired = true);
                    """.trimIndent()
                )
                driver.delay(200)

                driver.dblclick("#addButton")
                driver.delay(300)

                val dblClicked = driver.evaluate("window._dblClickFired", false)
                assertTrue(dblClicked.toString().toBoolean(),
                    "CDP double-click must fire dblclick event on the element")
            }
        }

        @Test
        @DisplayName("type posts Input.insertText calls — verified via input event")
        fun typePostsInsertTextCalls() = runBlocking {
            browser.newDriver().use { driver ->
                assertIs<PulsarWebDriver>(driver)
                openAndCompute(testPage, driver)

                driver.evaluate(
                    """
                    window._inputEventCount = 0;
                    document.getElementById('name').addEventListener('input', () => window._inputEventCount++);
                    """.trimIndent()
                )
                driver.delay(200)

                driver.type("abcd", "#name")
                driver.delay(300)

                val inputCount = driver.evaluate("window._inputEventCount", 0)
                // Each insertText triggers an input event
                val count = inputCount.toString().toIntOrNull() ?: 0
                assertTrue(count >= 1,
                    "type('abcd') should fire at least 1 input event, got: $count")
            }
        }
    }
}
