package ai.platon.pulsar.skeleton.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdkVersionsTest {

    @Test
    fun parseAcceptsFullVersion() {
        val parts = SdkVersions.parse("4.14.0")
        assertEquals(4, parts!!.major)
        assertEquals(14, parts.minor)
        assertEquals(0, parts.patch)
        assertNull(parts.qualifier)
    }

    @Test
    fun parseAcceptsSnapshotAndRcQualifiers() {
        assertEquals("SNAPSHOT", SdkVersions.parse("4.13.6-SNAPSHOT")!!.qualifier)
        assertEquals("rc.1", SdkVersions.parse("4.12.0-rc.1")!!.qualifier)
    }

    @Test
    fun parseDefaultsMissingPatchToZero() {
        assertEquals(0, SdkVersions.parse("4.14")!!.patch)
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(SdkVersions.parse(null))
        assertNull(SdkVersions.parse(""))
        assertNull(SdkVersions.parse("   "))
        assertNull(SdkVersions.parse("latest"))
        assertNull(SdkVersions.parse("4.x"))
    }

    @Test
    fun parseRejectsIntOverflowComponents() {
        // Mangled/overflowing numeric components must not throw NumberFormatException.
        assertNull(SdkVersions.parse("99999999999999999999.0.0"))
        assertNull(SdkVersions.parse("4.99999999999999999999.0"))
        assertNull(SdkVersions.parse("4.0.99999999999999999999"))
    }

    @Test
    fun majorOfReturnsMajorComponent() {
        assertEquals(4, SdkVersions.majorOf("4.14.0-SNAPSHOT"))
        assertEquals(5, SdkVersions.majorOf("5.0.0"))
        assertNull(SdkVersions.majorOf("bogus"))
    }

    @Test
    fun isSnapshotDetectsPreReleases() {
        assertTrue(SdkVersions.isSnapshot("4.14.0-SNAPSHOT"))
        assertTrue(SdkVersions.isSnapshot("4.14.0-snapshot"))
        assertFalse(SdkVersions.isSnapshot("4.14.0"))
        assertFalse(SdkVersions.isSnapshot("4.14.0-rc.1"))
    }
}
