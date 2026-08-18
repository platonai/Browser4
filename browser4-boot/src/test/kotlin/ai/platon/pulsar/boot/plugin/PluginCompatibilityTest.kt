package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PluginCompatibilityTest {

    private fun manifest(sdkVersion: String? = null, name: String = "test-plugin"): PluginManifest {
        return PluginManifest(
            name = name,
            version = "1.0.0",
            sdkVersion = sdkVersion.orEmpty(),
        )
    }

    @Test
    fun sameMajorIsCompatible() {
        val verdict = PluginCompatibility.check(manifest("4.12.0"), hostVersion = "4.14.0")
        assertInstanceOf(PluginCompatibility.Compatible::class.java, verdict)
        assertEquals("4.12.0", verdict.sdkVersion)
    }

    @Test
    fun sameVersionIsCompatible() {
        val verdict = PluginCompatibility.check(manifest("4.14.0-SNAPSHOT"), hostVersion = "4.14.0-SNAPSHOT")
        assertInstanceOf(PluginCompatibility.Compatible::class.java, verdict)
    }

    @Test
    fun olderMajorIsWarnedButNotBlocked() {
        val verdict = PluginCompatibility.check(manifest("3.9.0"), hostVersion = "4.14.0")
        val warn = assertInstanceOf(PluginCompatibility.Warn::class.java, verdict)
        assertEquals("3.9.0", warn.sdkVersion)
        assert(warn.reason.contains("backward compatibility"))
    }

    @Test
    fun newerMajorIsBlocked() {
        val verdict = PluginCompatibility.check(manifest("5.0.0"), hostVersion = "4.14.0")
        val blocked = assertInstanceOf(PluginCompatibility.Blocked::class.java, verdict)
        assert(blocked.reason.contains("requires SDK 5.0.0"))
        assert(blocked.reason.contains("4.14.0"))
    }

    @Test
    fun missingSdkVersionIsWarnedWithNullVersion() {
        val verdict = PluginCompatibility.check(manifest(null), hostVersion = "4.14.0")
        val warn = assertInstanceOf(PluginCompatibility.Warn::class.java, verdict)
        assertEquals(null, warn.sdkVersion)
        assert(warn.reason.contains("no sdkVersion"))
    }

    @Test
    fun unparseableSdkVersionIsWarnedNotBlocked() {
        val verdict = PluginCompatibility.check(manifest("latest"), hostVersion = "4.14.0")
        val warn = assertInstanceOf(PluginCompatibility.Warn::class.java, verdict)
        assert(warn.reason.contains("unparseable"))
    }

    @Test
    fun unknownHostVersionNeverBlocks() {
        // The Browser4Version.UNKNOWN sentinel is unparseable, so the policy
        // cannot judge compatibility and must not refuse the plugin.
        val verdict = PluginCompatibility.check(manifest("9.0.0"), hostVersion = Browser4Version.UNKNOWN)
        assertInstanceOf(PluginCompatibility.Compatible::class.java, verdict)
    }

    @Test
    fun snapshotPluginWithNewerMajorIsStillBlocked() {
        val verdict = PluginCompatibility.check(manifest("5.0.0-SNAPSHOT"), hostVersion = "4.14.0-SNAPSHOT")
        assertInstanceOf(PluginCompatibility.Blocked::class.java, verdict)
    }
}
