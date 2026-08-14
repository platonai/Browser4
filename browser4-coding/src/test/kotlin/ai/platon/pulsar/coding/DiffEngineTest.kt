package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DiffEngineTest {

    private fun lines(vararg s: String) = s.toList()

    // ==================== Myers correctness ====================

    @Test
    @DisplayName("myers: identical inputs produce all-Equal edits")
    fun myersIdentical() {
        val edits = DiffEngine.diff(lines("a", "b", "c"), lines("a", "b", "c"))
        assertEquals(3, edits.size)
        assertTrue(edits.all { it is DiffEngine.Edit.Equal })
    }

    @Test
    @DisplayName("myers: single line insert in the middle stays aligned")
    fun myersMidInsert() {
        val old = lines("line1", "line2", "line3", "line4", "line5")
        val new = lines("line1", "line2", "INSERTED", "line3", "line4", "line5")
        val edits = DiffEngine.diff(old, new)

        assertEquals(1, edits.count { it is DiffEngine.Edit.Insert })
        assertTrue(edits.any { it is DiffEngine.Edit.Insert && it.line == "INSERTED" })
        // The naive algorithm would have flagged line3/line4/line5 as delete+insert;
        // Myers must keep them as Equal.
        assertEquals(5, edits.count { it is DiffEngine.Edit.Equal })
    }

    @Test
    @DisplayName("myers: single line delete in the middle")
    fun myersMidDelete() {
        val old = lines("a", "b", "c", "d", "e")
        val new = lines("a", "b", "d", "e")
        val edits = DiffEngine.diff(old, new)
        assertEquals(1, edits.count { it is DiffEngine.Edit.Delete })
        assertEquals(4, edits.count { it is DiffEngine.Edit.Equal })
    }

    @Test
    @DisplayName("myers: replacement is delete+insert pair")
    fun myersReplace() {
        val old = lines("a", "OLD", "c")
        val new = lines("a", "NEW", "c")
        val edits = DiffEngine.diff(old, new)
        assertEquals(1, edits.count { it is DiffEngine.Edit.Delete })
        assertEquals(1, edits.count { it is DiffEngine.Edit.Insert })
        assertEquals(2, edits.count { it is DiffEngine.Edit.Equal })
    }

    @Test
    @DisplayName("myers: empty old input is all inserts")
    fun myersEmptyOld() {
        val edits = DiffEngine.diff(emptyList(), lines("a", "b"))
        assertEquals(2, edits.count { it is DiffEngine.Edit.Insert })
    }

    @Test
    @DisplayName("myers: empty new input is all deletes")
    fun myersEmptyNew() {
        val edits = DiffEngine.diff(lines("a", "b"), emptyList())
        assertEquals(2, edits.count { it is DiffEngine.Edit.Delete })
    }

    @Test
    @DisplayName("myers: adjacent changes produce valid edit script")
    fun myersAdjacentChanges() {
        val old = lines("a", "b", "c", "d", "e")
        val new = lines("x", "c", "d", "z")
        val edits = DiffEngine.diff(old, new)
        // Reconstruct the new file from the edit script to prove validity.
        val rebuilt = StringBuilder()
        edits.forEach { e ->
            when (e) {
                is DiffEngine.Edit.Equal -> rebuilt.append(e.line).append('\n')
                is DiffEngine.Edit.Insert -> rebuilt.append(e.line).append('\n')
                is DiffEngine.Edit.Delete -> {}
            }
        }
        assertEquals(new.joinToString("\n") + "\n", rebuilt.toString())
    }

    // ==================== Patience ====================

    @Test
    @DisplayName("patience: function move is recognized via unique anchors")
    fun patienceFunctionMove() {
        val old = lines(
            "fun alpha() {", "  return 1;", "}",
            "fun beta() {", "  return 2;", "}",
            "fun gamma() {", "  return 3;", "}"
        )
        val new = lines(
            "fun alpha() {", "  return 1;", "}",
            "fun gamma() {", "  return 3;", "}",
            "fun beta() {", "  return 2;", "}"
        )
        val edits = DiffEngine.diff(old, new, algorithm = "patience")
        // beta block moved: delete 3 lines and insert 3 lines somewhere else.
        val deletes = edits.count { it is DiffEngine.Edit.Delete }
        val inserts = edits.count { it is DiffEngine.Edit.Insert }
        assertEquals(3, deletes, "expected the beta block (3 lines) to be deleted once")
        assertEquals(3, inserts, "expected the beta block (3 lines) to be inserted once")
        // alpha and gamma bodies stay as Equal.
        assertTrue(edits.count { it is DiffEngine.Edit.Equal } >= 4)
    }

    @Test
    @DisplayName("patience: repeated boilerplate lines do not confuse anchoring")
    fun patienceRepeatedBoilerplate() {
        val old = lines("}", "}", "}", "}", "fun main()", "}", "}")
        val new = lines("}", "}", "}", "}", "fun main() {", "}", "}")
        val edits = DiffEngine.diff(old, new, algorithm = "patience")
        // Only the changed line should differ.
        assertEquals(1, edits.count { it is DiffEngine.Edit.Delete })
        assertEquals(1, edits.count { it is DiffEngine.Edit.Insert })
    }

    @Test
    @DisplayName("patience: falls back to myers for tiny inputs")
    fun patienceSmallFallback() {
        val old = lines("a", "b")
        val new = lines("a", "c")
        val edits = DiffEngine.diff(old, new, algorithm = "patience")
        assertEquals(1, edits.count { it is DiffEngine.Edit.Delete })
        assertEquals(1, edits.count { it is DiffEngine.Edit.Insert })
    }

    // ==================== Unified diff rendering ====================

    @Test
    @DisplayName("toUnified returns null when no changes")
    fun unifiedNoChanges() {
        val edits = DiffEngine.diff(lines("a"), lines("a"))
        assertNull(DiffEngine.toUnified("old.txt", "new.txt", edits))
    }

    @Test
    @DisplayName("toUnified produces standard headers and hunk")
    fun unifiedHeaders() {
        val edits = DiffEngine.diff(lines("a", "b", "c", "d"), lines("a", "B", "c", "d"))
        val out = DiffEngine.toUnified("old.txt", "new.txt", edits)!!
        assertTrue(out.startsWith("--- a/old.txt\n+++ b/new.txt\n"))
        assertTrue(out.contains("@@ -1,4 +1,4 @@"))
        assertTrue(out.contains("-b"))
        assertTrue(out.contains("+B"))
    }

    @Test
    @DisplayName("toUnified splits distant changes into separate hunks")
    fun unifiedMultipleHunks() {
        val old = (1..50).map { "line$it" }
        val new = (1..50).map { if (it == 5 || it == 45) "CHANGED$it" else "line$it" }
        val edits = DiffEngine.diff(old, new)
        val out = DiffEngine.toUnified("a.txt", "b.txt", edits, context = 2)!!
        // Two hunks: one near line 5, one near line 45.
        assertEquals(2, Regex("@@ -").findAll(out).count())
    }

    @Test
    @DisplayName("unified diff round-trips: applying edits reconstructs the new file")
    fun unifiedRoundTrip() {
        val old = lines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val new = lines("a", "X", "c", "d", "e", "f", "Y", "h", "i", "Z")
        val edits = DiffEngine.diff(old, new)
        val rebuilt = StringBuilder()
        edits.forEach { e ->
            when (e) {
                is DiffEngine.Edit.Equal -> rebuilt.append(e.line).append('\n')
                is DiffEngine.Edit.Insert -> rebuilt.append(e.line).append('\n')
                is DiffEngine.Edit.Delete -> {}
            }
        }
        assertEquals(new.joinToString("\n") + "\n", rebuilt.toString())
    }

    @Test
    @DisplayName("algorithm dispatch defaults to myers and accepts patience")
    fun algorithmDispatch() {
        val old = lines("a", "b", "c")
        val new = lines("a", "c")
        assertTrue(DiffEngine.diff(old, new).all { it !is DiffEngine.Edit.Insert || it.line != "b" })
        assertEquals(1, DiffEngine.diff(old, new, "myers").count { it is DiffEngine.Edit.Delete })
        assertEquals(1, DiffEngine.diff(old, new, "patience").count { it is DiffEngine.Edit.Delete })
    }
}


