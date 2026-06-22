package ai.platon.browser4.importer.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrowserDataLocatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testParseFirefoxProfilesIni_singleDefaultProfile() {
        val iniContent = """
            [General]
            StartWithLastProfile=1

            [Profile0]
            Name=default
            IsRelative=1
            Path=Profiles/abc123.default
            Default=yes
        """.trimIndent()

        val profilesIni = tempDir.resolve("profiles.ini")
        Files.writeString(profilesIni, iniContent)

        // Create the actual profile directory
        val profileDir = tempDir.resolve("Profiles/abc123.default")
        Files.createDirectories(profileDir)

        val result = BrowserDataLocator.parseFirefoxProfilesIni(profilesIni)
        assertEquals(1, result.size, "Should find one profile")
        assertTrue(result[0].endsWith("Profiles/abc123.default"),
            "Profile path should resolve relative to profiles.ini directory")
    }

    @Test
    fun testParseFirefoxProfilesIni_multipleProfiles() {
        val iniContent = """
            [General]
            StartWithLastProfile=1

            [Profile0]
            Name=default
            IsRelative=1
            Path=Profiles/abc123.default
            Default=yes

            [Profile1]
            Name=dev
            IsRelative=0
            Path=${tempDir.resolve("Profiles/custom-dev").toString().replace('\\', '/')}
        """.trimIndent()

        val profilesIni = tempDir.resolve("profiles.ini")
        Files.writeString(profilesIni, iniContent)

        // Create both profile directories
        Files.createDirectories(tempDir.resolve("Profiles/abc123.default"))
        Files.createDirectories(tempDir.resolve("Profiles/custom-dev"))

        val result = BrowserDataLocator.parseFirefoxProfilesIni(profilesIni)
        assertEquals(2, result.size, "Should find two profiles")
    }

    @Test
    fun testParseFirefoxProfilesIni_noProfiles() {
        val iniContent = """
            [General]
            StartWithLastProfile=1
        """.trimIndent()

        val profilesIni = tempDir.resolve("profiles.ini")
        Files.writeString(profilesIni, iniContent)

        val result = BrowserDataLocator.parseFirefoxProfilesIni(profilesIni)
        assertEquals(0, result.size, "Should find no profiles")
    }

    @Test
    fun testParseFirefoxProfilesIni_missingFile() {
        val missingFile = tempDir.resolve("nonexistent.ini")
        val result = BrowserDataLocator.parseFirefoxProfilesIni(missingFile)
        assertEquals(0, result.size, "Should return empty list for missing file")
    }

    @Test
    fun testParseFirefoxProfilesIni_directoryDoesNotExist() {
        val iniContent = """
            [Profile0]
            Name=ghost
            IsRelative=1
            Path=Profiles/ghost.default
            Default=yes
        """.trimIndent()

        val profilesIni = tempDir.resolve("profiles.ini")
        Files.writeString(profilesIni, iniContent)

        // Don't create the profile directory — should be skipped
        val result = BrowserDataLocator.parseFirefoxProfilesIni(profilesIni)
        assertEquals(0, result.size, "Should skip profiles whose directories don't exist")
    }

    @Test
    fun testLocateAllAvailable_runsWithoutCrash() {
        // Just verify the method doesn't throw — the actual result depends on
        // which browsers are installed on the current machine.
        val available = BrowserDataLocator.locateAllAvailable()
        // The result could be anything; we just care it doesn't throw.
        assertTrue(available is Map<*, *>, "Should return a Map")
    }
}
