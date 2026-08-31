package ai.platon.pulsar.profileimport.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileCopierTest {

    private fun createFakeProfile(userDataDir: Path): SourceProfile {
        val profileDir = userDataDir.resolve("Default").createDirectories()
        profileDir.resolve("Bookmarks").writeText("""{"roots":{}}""")
        profileDir.resolve("History").writeText("sqlite-bytes")
        profileDir.resolve("Preferences").writeText("{}")
        profileDir.resolve("Network").createDirectories().resolve("Cookies").writeText("cookies")
        val extDir = profileDir.resolve("Extensions").createDirectories().resolve("abc").createDirectories()
        extDir.resolve("manifest.json").writeText("{}")
        profileDir.resolve("Cache").createDirectories().resolve("f").writeText("cache")
        profileDir.resolve("SingletonLock").writeText("lock")
        userDataDir.resolve("Local State").writeText("""{"os_crypt":{"encrypted_key":"x"}}""")
        return SourceProfile(
            browser = "chrome",
            directory = "Default",
            name = "Person 1",
            userDataDir = userDataDir,
            profileDir = profileDir,
        )
    }

    @Test
    fun `copyProfile copies data but excludes caches and lock files`() {
        val source = Files.createTempDirectory("b4-src")
        val profile = createFakeProfile(source)
        // The source browser is not running in this test — drop the lock.
        Files.deleteIfExists(profile.profileDir.resolve("SingletonLock"))
        val dest = Files.createTempDirectory("b4-dst")

        val copied = ProfileCopier().copyProfile(source, profile, dest)

        assertTrue(copied >= 5)
        val dstProfile = dest.resolve("Default")
        assertTrue(Files.exists(dstProfile.resolve("Bookmarks")))
        assertTrue(Files.exists(dstProfile.resolve("History")))
        assertTrue(Files.exists(dstProfile.resolve("Network/Cookies")))
        assertTrue(Files.exists(dstProfile.resolve("Extensions/abc/manifest.json")))
        assertFalse(Files.exists(dstProfile.resolve("Cache")))
        assertFalse(Files.exists(dstProfile.resolve("SingletonLock")))
        assertTrue(Files.exists(dest.resolve("Local State")))
    }

    @Test
    fun `copyProfile fails loudly when the source browser is running`() {
        val source = Files.createTempDirectory("b4-src")
        val profile = createFakeProfile(source)
        val dest = Files.createTempDirectory("b4-dst")

        val e = assertFailsWith<IllegalStateException> {
            ProfileCopier().copyProfile(source, profile, dest)
        }
        assertTrue(e.message!!.contains("running"))
    }

    @Test
    fun `copyProfile fails for missing profile directory`() {
        val source = Files.createTempDirectory("b4-src")
        val dest = Files.createTempDirectory("b4-dst")
        val profile = SourceProfile(
            browser = "chrome",
            directory = "Nope",
            name = "Nope",
            userDataDir = source,
            profileDir = source.resolve("Nope"),
        )

        val e = assertFailsWith<IllegalArgumentException> {
            ProfileCopier().copyProfile(source, profile, dest)
        }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun `copyProfile with lock file removed succeeds`() {
        val source = Files.createTempDirectory("b4-src")
        val profile = createFakeProfile(source)
        Files.deleteIfExists(profile.profileDir.resolve("SingletonLock"))
        val dest = Files.createTempDirectory("b4-dst")

        val copied = ProfileCopier().copyProfile(source, profile, dest)
        assertEquals(5, copied)
    }

    @Test
    fun `exclusion and lock tables are stable`() {
        assertTrue(ProfileCopier.EXCLUDE_DIRS.containsAll(listOf("Cache", "Code Cache", "GPUCache")))
        assertTrue(ProfileCopier.LOCK_FILES.containsAll(listOf("SingletonLock", "SingletonSocket", "SingletonCookie")))
    }
}
