package ai.platon.browser4.chrome.protocol

import ai.platon.browser4.chrome.protocol.normalizeKeyStringForPress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyboardKeyStringNormalizationTest {
    @Test
    fun normalizeKeyStringForPressLeavesRegularKeysUnchanged() {
        assertEquals("a", normalizeKeyStringForPress("a"))
        assertEquals("Enter", normalizeKeyStringForPress("Enter"))
        assertEquals("Control+Shift+Tab", normalizeKeyStringForPress("Control+Shift+Tab"))
    }

    @Test
    fun normalizeKeyStringForPressExpandsUppercaseLettersToShiftCombos() {
        assertEquals("Shift+a", normalizeKeyStringForPress("A"))
        assertEquals("Shift+z", normalizeKeyStringForPress("Z"))
    }

    @Test
    fun normalizeKeyStringForPressExpandsShiftedPunctuationToBaseKeyCombos() {
        assertEquals("Shift+1", normalizeKeyStringForPress("!"))
        assertEquals("Shift+0", normalizeKeyStringForPress(")"))
        assertEquals("Shift+/", normalizeKeyStringForPress("?"))
        assertEquals("Shift+=", normalizeKeyStringForPress("+"))
        assertEquals("Shift+;", normalizeKeyStringForPress(":"))
    }
}


