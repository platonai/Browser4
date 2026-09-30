package ai.platon.pulsar.profileimport.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChromeProfileReaderTest {

    private fun createLocalState(userDataDir: Path, profiles: Map<String, String>) {
        val entries = profiles.entries.joinToString(",") { (dir, name) ->
            "\"$dir\":{\"name\":\"$name\"}"
        }
        userDataDir.resolve("Local State").writeText("""{"profile":{"info_cache":{$entries}}}""")
    }

    @Test
    fun `listProfiles reads profiles from Local State`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Person 1", "Profile 1" to "Work"))

        val profiles = ChromeProfileReader.listProfiles(dir, "chrome")

        assertEquals(2, profiles.size)
        assertEquals("Default", profiles[0].directory)
        assertEquals("Person 1", profiles[0].name)
        assertEquals("Profile 1", profiles[1].directory)
        assertEquals("Work", profiles[1].name)
    }

    @Test
    fun `listProfiles returns empty for missing Local State`() {
        val dir = Files.createTempDirectory("b4-profiles")
        assertTrue(ChromeProfileReader.listProfiles(dir, "chrome").isEmpty())
    }

    @Test
    fun `resolveProfile matches exact directory name`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Person 1", "Profile 1" to "Work"))

        val resolved = ChromeProfileReader.resolveProfile(dir, "chrome", "Profile 1")
        assertEquals("Profile 1", resolved.directory)
    }

    @Test
    fun `resolveProfile matches display name case-insensitively`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Person 1"))

        val resolved = ChromeProfileReader.resolveProfile(dir, "chrome", "person 1")
        assertEquals("Default", resolved.directory)
    }

    @Test
    fun `resolveProfile fails for unknown profile with available list`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Person 1"))

        val e = assertFailsWith<IllegalArgumentException> {
            ChromeProfileReader.resolveProfile(dir, "chrome", "nope")
        }
        assertTrue(e.message!!.contains("Default"))
    }

    @Test
    fun `resolveProfile fails on ambiguous display names`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Same", "Profile 1" to "Same"))

        val e = assertFailsWith<IllegalArgumentException> {
            ChromeProfileReader.resolveProfile(dir, "chrome", "same")
        }
        assertTrue(e.message!!.contains("Ambiguous"))
    }

    @Test
    fun `resolveProfile defaults to first profile for blank input`() {
        val dir = Files.createTempDirectory("b4-profiles")
        createLocalState(dir, mapOf("Default" to "Person 1", "Profile 1" to "Work"))

        assertEquals("Default", ChromeProfileReader.resolveProfile(dir, "chrome", "").directory)
    }
}
