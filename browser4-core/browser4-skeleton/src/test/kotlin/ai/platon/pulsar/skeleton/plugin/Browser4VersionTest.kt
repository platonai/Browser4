package ai.platon.pulsar.skeleton.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Browser4VersionTest {

    @Test
    fun versionIsPlausibleSemVer() {
        // The build filters META-INF/browser4-version.properties from the
        // module version (governed == repo VERSION), so this must never be
        // blank or the placeholder.
        val version = Browser4Version.version
        assertFalse(version.isBlank())
        assertFalse(version.contains("\${project.version}"), "version property was not filtered: $version")
        assertTrue(
            SdkVersions.parse(version) != null,
            "host version '$version' should be a plausible X.Y.Z(-qualifier) version"
        )
    }
}
