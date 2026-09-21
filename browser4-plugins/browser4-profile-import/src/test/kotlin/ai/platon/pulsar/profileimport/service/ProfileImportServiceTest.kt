package ai.platon.pulsar.profileimport.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileImportServiceTest {

    private fun createChromiumSource(root: Path): Path {
        val userData = root.resolve("user-data").createDirectories()
        userData.resolve("Local State").writeText("""{"profile":{"info_cache":{"Default":{"name":"Person 1"}}}}""")
        val profile = userData.resolve("Default").createDirectories()
        profile.resolve("Bookmarks").writeText("""{"roots":{}}""")
        profile.resolve("History").writeText("h")
        profile.resolve("Login Data").writeText("l")
        profile.resolve("Network").createDirectories().resolve("Cookies").writeText("c")
        profile.resolve("Extensions").createDirectories().resolve("x").createDirectories()
            .resolve("manifest.json").writeText("{}")
        return userData
    }

    private fun service(
        importRoot: Path,
        allowPasswords: Boolean = false,
        userDataDir: Path? = null,
        safariPaths: Map<String, Path?> = emptyMap(),
    ): ProfileImportService {
        val detector = object : SourceBrowserDetector() {
            override fun findUserDataDir(browser: String): Path? = userDataDir
            override fun listProfiles(browser: String): List<SourceProfile> =
                if (userDataDir != null) ChromeProfileReader.listProfiles(userDataDir, browser) else emptyList()
            override fun safariPaths(): Map<String, Path?> = safariPaths
        }
        // browser4Root points at the temp dir so `--into prototype/default`
        // never touches the real ~/.browser4 in tests.
        val browser4Root = importRoot.parent ?: importRoot
        return ProfileImportService(
            detector = detector,
            copier = ProfileCopier(),
            safariReader = SafariDataReader(),
            importRoot = importRoot,
            allowPasswords = allowPasswords,
            browser4Root = browser4Root,
        )
    }

    @Test
    fun `import copies the whole Chrome profile by default`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val result = service.import("chrome", null, null, null)

        val importDir = Path.of(result["importDir"].toString())
        val profileDir = Path.of(result["profileDir"].toString())
        assertTrue(Files.exists(profileDir.resolve("Bookmarks")))
        assertTrue(Files.exists(profileDir.resolve("History")))
        assertTrue(Files.exists(profileDir.resolve("Network/Cookies")))
        assertTrue(Files.exists(profileDir.resolve("Extensions/x/manifest.json")))
        assertTrue(Files.exists(importDir.resolve("meta.json")))
        assertEquals("chrome:Default", result["sourceProfile"])
        assertTrue(result["warnings"].toString().contains("Passwords were not imported"))
    }

    @Test
    fun `import prunes unrequested data`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val result = service.import("chrome", null, "bookmarks,cookies", null)

        val profileDir = Path.of(result["profileDir"].toString())
        assertTrue(Files.exists(profileDir.resolve("Bookmarks")))
        assertTrue(Files.exists(profileDir.resolve("Network/Cookies")))
        assertTrue(!Files.exists(profileDir.resolve("History")))
        assertTrue(!Files.exists(profileDir.resolve("Login Data")))
        assertTrue(!Files.exists(profileDir.resolve("Extensions")))
    }

    @Test
    fun `import rejects passwords unless explicitly allowed`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val e = assertFailsWith<IllegalArgumentException> {
            service.import("chrome", null, "passwords", null)
        }
        assertTrue(e.message!!.contains("profileimport.allow.passwords"))
    }

    @Test
    fun `import copies Login Data when passwords are allowed`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(
            importRoot = root.resolve("imports"),
            allowPasswords = true,
            userDataDir = userData,
        )

        val result = service.import("chrome", null, "passwords", null)

        val profileDir = Path.of(result["profileDir"].toString())
        assertTrue(Files.exists(profileDir.resolve("Login Data")))
    }

    @Test
    fun `import fails on running source browser`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        userData.resolve("Default").resolve("SingletonLock").writeText("lock")
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val e = assertFailsWith<IllegalStateException> {
            service.import("chrome", null, null, null)
        }
        assertTrue(e.message!!.contains("running"))
    }

    @Test
    fun `import rejects unknown data types`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val e = assertFailsWith<IllegalArgumentException> {
            service.import("chrome", null, "bookmarks,passwords2", null)
        }
        assertTrue(e.message!!.contains("passwords2"))
    }

    @Test
    fun `import lands in prototype dir with Local State`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val result = service.import("chrome", null, "bookmarks", "prototype")

        val target = Path.of(result["landedAt"].toString())
        assertTrue(
            target.toString().contains("prototype") && target.toString().contains("google-chrome"),
            "target: $target"
        )
        assertTrue(Files.exists(target.resolve("Bookmarks")), "Bookmarks missing in $target: ${Files.list(target).use { it.toList() }}")
        assertTrue(Files.exists(target.resolve("Local State")), "Local State missing in $target")
        assertTrue(!Files.exists(target.resolve("History")), "History should be pruned in $target")
    }

    @Test
    fun `import refuses to overwrite a non-empty prototype dir`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)
        val prototype = root.resolve("browser/chrome/prototype/google-chrome")
        prototype.resolve("Existing").toFile().mkdirs()

        val e = assertFailsWith<IllegalStateException> {
            service.import("chrome", null, "bookmarks", "prototype")
        }
        assertTrue(e.message!!.contains("not empty"))
    }

    @Test
    fun `import rejects unknown into values`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val e = assertFailsWith<IllegalArgumentException> {
            service.import("chrome", null, "bookmarks", "somewhere-else")
        }
        assertTrue(e.message!!.contains("prototype"))
    }

    @Test
    fun `safari import converts bookmarks and cookies`() {
        val root = Files.createTempDirectory("b4-import")
        val plist = root.resolve("Bookmarks.plist")
        plist.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Children</key><array>
    <dict>
      <key>WebBookmarkType</key><string>WebBookmarkTypeLeaf</string>
      <key>WebBookmarkTitle</key><string>Example</string>
      <key>URLString</key><string>https://example.com</string>
    </dict>
  </array>
</dict></plist>"""
        )
        val cookiesFile = root.resolve("Cookies.binarycookies")
        Files.write(cookiesFile, buildMinimalBinaryCookies("example.com", "s", "v"))
        val service = service(
            importRoot = root.resolve("imports"),
            safariPaths = mapOf("bookmarks" to plist, "cookies" to cookiesFile),
        )

        val result = service.import("safari", null, "bookmarks,cookies", null)

        val importDir = Path.of(result["importDir"].toString())
        assertTrue(Files.exists(importDir.resolve("profile/Bookmarks")))
        assertTrue(Files.exists(importDir.resolve("cookies.json")))
        assertEquals(1, result["bookmarksImported"])
        assertEquals(1, result["cookiesImported"])
        assertTrue(result["warnings"].toString().isEmpty() || result["warnings"].toString() == "[]")
    }

    @Test
    fun `safari import warns about unsupported data`() {
        val root = Files.createTempDirectory("b4-import")
        val service = service(importRoot = root.resolve("imports"))

        val result = service.import("safari", null, "bookmarks,history,passwords,extensions", null)

        assertTrue(result["warnings"].toString().contains("history"))
        assertTrue(result["warnings"].toString().contains("Keychain"))
        assertNotNull(result["importDir"])
    }

    @Test
    fun `listSources returns all three browsers`() {
        val root = Files.createTempDirectory("b4-import")
        val userData = createChromiumSource(root)
        val service = service(importRoot = root.resolve("imports"), userDataDir = userData)

        val sources = service.listSources()

        assertTrue(sources.containsKey("chrome"))
        assertTrue(sources.containsKey("edge"))
        assertTrue(sources.containsKey("safari"))
        val chrome = sources["chrome"] as List<*>
        assertEquals(1, chrome.size)
    }

    /** Minimal single-cookie binarycookies file (shared with SafariDataReaderTest). */
    private fun buildMinimalBinaryCookies(domain: String, name: String, value: String): ByteArray {
        val domainB = domain.toByteArray(Charsets.UTF_8)
        val nameB = name.toByteArray(Charsets.UTF_8)
        val pathB = "/".toByteArray(Charsets.UTF_8)
        val valueB = value.toByteArray(Charsets.UTF_8)
        var offset = 72
        val dOff = offset; offset += domainB.size + 1
        val nOff = offset; offset += nameB.size + 1
        val pOff = offset; offset += pathB.size + 1
        val vOff = offset; offset += valueB.size + 1

        val cookie = java.nio.ByteBuffer.allocate(offset).order(java.nio.ByteOrder.BIG_ENDIAN)
        cookie.putInt(offset); cookie.putInt(0); cookie.putInt(0); cookie.putInt(0)
        cookie.putLong(0); cookie.putLong(0)
        cookie.putInt(dOff); cookie.putInt(nOff); cookie.putInt(pOff); cookie.putInt(vOff)
        cookie.putInt(0); cookie.putInt(0); cookie.putInt(0); cookie.putInt(0); cookie.putInt(0); cookie.putInt(0)
        cookie.put(domainB); cookie.put(0.toByte())
        cookie.put(nameB); cookie.put(0.toByte())
        cookie.put(pathB); cookie.put(0.toByte())
        cookie.put(valueB); cookie.put(0.toByte())

        val page = java.nio.ByteBuffer.allocate(4 + 4 + 8 + offset).order(java.nio.ByteOrder.BIG_ENDIAN)
        page.putInt(0x00000100); page.putInt(1); page.putInt(16); page.putInt(16 + offset)
        page.put(cookie.array())

        val out = java.io.ByteArrayOutputStream()
        out.write("cook".toByteArray(Charsets.US_ASCII))
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(1).array())
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(page.array().size).array())
        out.write(page.array())
        return out.toByteArray()
    }
}
