package ai.platon.pulsar.profileimport.service

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafariDataReaderTest {

    private val reader = SafariDataReader()

    // ------------------------------------------------------------------
    // Bookmarks plist
    // ------------------------------------------------------------------

    private val samplePlist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>WebBookmarkVersion</key><integer>1</integer>
  <key>Children</key><array>
    <dict>
      <key>WebBookmarkType</key><string>WebBookmarkTypeList</string>
      <key>WebBookmarkTitle</key><string>Folder A</string>
      <key>Children</key><array>
        <dict>
          <key>WebBookmarkType</key><string>WebBookmarkTypeLeaf</string>
          <key>WebBookmarkTitle</key><string>Example</string>
          <key>URLString</key><string>https://example.com</string>
        </dict>
      </array>
    </dict>
    <dict>
      <key>WebBookmarkType</key><string>WebBookmarkTypeLeaf</string>
      <key>WebBookmarkTitle</key><string>Direct</string>
      <key>URLString</key><string>https://direct.test</string>
    </dict>
  </array>
</dict></plist>"""

    @Test
    fun `parseBookmarks reads nested structure`() {
        val file = Files.createTempFile("b4-bookmarks", ".plist")
        file.writeText(samplePlist)

        val bookmarks = reader.parseBookmarks(file)

        assertEquals(2, bookmarks.size)
        assertEquals("Folder A", bookmarks[0].title)
        assertEquals(null, bookmarks[0].url)
        assertEquals(1, bookmarks[0].children.size)
        assertEquals("Example", bookmarks[0].children[0].title)
        assertEquals("https://example.com", bookmarks[0].children[0].url)
        assertEquals("Direct", bookmarks[1].title)
    }

    @Test
    fun `writeChromeBookmarks produces a Chrome-format JSON file`() {
        val file = Files.createTempFile("b4-bookmarks", ".plist")
        file.writeText(samplePlist)
        val bookmarks = reader.parseBookmarks(file)
        val out = Files.createTempDirectory("b4-out").resolve("Bookmarks")

        reader.writeChromeBookmarks(bookmarks, out)

        val text = out.toFile().readText()
        assertTrue(text.contains("\"version\" : 1"))
        assertTrue(text.contains("\"bookmark_bar\""))
        assertTrue(text.contains("https://example.com"))
        assertTrue(text.contains("\"type\" : \"folder\""))
    }

    // ------------------------------------------------------------------
    // Cookies.binarycookies
    // ------------------------------------------------------------------

    /** Builds a minimal single-cookie binarycookies byte array. */
    private fun buildBinaryCookies(
        domain: String,
        name: String,
        value: String,
        path: String = "/",
        flags: Int = 0,
        expiryMacAbsolute: Long = 0,
    ): ByteArray {
        val domainB = domain.toByteArray(Charsets.UTF_8)
        val nameB = name.toByteArray(Charsets.UTF_8)
        val pathB = path.toByteArray(Charsets.UTF_8)
        val valueB = value.toByteArray(Charsets.UTF_8)

        // cookie struct: 72 fixed bytes + 4 null-terminated strings
        var offset = 72
        val domainOffset = offset; offset += domainB.size + 1
        val nameOffset = offset; offset += nameB.size + 1
        val pathOffset = offset; offset += pathB.size + 1
        val valueOffset = offset; offset += valueB.size + 1
        val cookieSize = offset

        val cookie = ByteBuffer.allocate(cookieSize).order(ByteOrder.BIG_ENDIAN)
        cookie.putInt(cookieSize)
        cookie.putInt(0)
        cookie.putInt(flags)
        cookie.putInt(0)
        cookie.putLong(0) // creation
        cookie.putLong(expiryMacAbsolute)
        cookie.putInt(domainOffset)
        cookie.putInt(nameOffset)
        cookie.putInt(pathOffset)
        cookie.putInt(valueOffset)
        cookie.putInt(0) // unknown
        cookie.putInt(0) // comment offset
        cookie.putInt(0) // comment length
        cookie.putInt(0)
        cookie.putInt(0)
        cookie.putInt(0)
        cookie.put(domainB); cookie.put(0.toByte())
        cookie.put(nameB); cookie.put(0.toByte())
        cookie.put(pathB); cookie.put(0.toByte())
        cookie.put(valueB); cookie.put(0.toByte())

        val pageBody = ByteBuffer.allocate(4 + 4 + 8 + cookieSize).order(ByteOrder.BIG_ENDIAN)
        pageBody.putInt(0x00000100)
        pageBody.putInt(1)
        pageBody.putInt(16) // first cookie offset (after page header + offsets)
        pageBody.putInt(16 + cookieSize)
        pageBody.put(cookie.array())
        val page = pageBody.array()

        val out = ByteArrayOutputStream()
        out.write("cook".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(page.size).array())
        out.write(page)
        return out.toByteArray()
    }

    @Test
    fun `parseCookies reads domain name value path`() {
        val file = Files.createTempFile("b4-cookies", ".binarycookies")
        Files.write(file, buildBinaryCookies("example.com", "session", "abc123"))

        val cookies = reader.parseCookies(file)

        assertEquals(1, cookies.size)
        assertEquals("example.com", cookies[0].domain)
        assertEquals("session", cookies[0].name)
        assertEquals("abc123", cookies[0].value)
        assertEquals("/", cookies[0].path)
        assertEquals(null, cookies[0].expires) // session cookie
    }

    @Test
    fun `parseCookies reads secure flag and expiry`() {
        // The file stores mac absolute time (seconds since 2001-01-01);
        // unix 1768262400 (2026-01-12T08:00:00Z) becomes 1768262400 - 978307200.
        val macAbsolute = 1_768_262_400L - 978_307_200L
        val file = Files.createTempFile("b4-cookies", ".binarycookies")
        Files.write(file, buildBinaryCookies(".example.com", "token", "v", flags = 0x05, expiryMacAbsolute = macAbsolute))

        val cookies = reader.parseCookies(file)

        assertEquals(1, cookies.size)
        assertEquals("example.com", cookies[0].domain) // leading dot stripped
        assertTrue(cookies[0].secure)
        assertTrue(cookies[0].httpOnly) // bit 2 (0x04)
        assertEquals(1_768_262_400L, cookies[0].expires)
    }

    @Test
    fun `parseCookies rejects non-binarycookies files`() {
        val file = Files.createTempFile("b4-cookies", ".bin")
        file.writeText("not a cookie file")

        val e = kotlin.test.assertFailsWith<IllegalArgumentException> {
            reader.parseCookies(file)
        }
        assertTrue(e.message!!.contains("not a binarycookies"))
    }

    @Test
    fun `writeCookiesJson produces a JSON array`() {
        val file = Files.createTempFile("b4-cookies", ".binarycookies")
        Files.write(file, buildBinaryCookies("example.com", "session", "abc123"))
        val cookies = reader.parseCookies(file)
        val out = Files.createTempDirectory("b4-out").resolve("cookies.json")

        reader.writeCookiesJson(cookies, out)

        val text = out.toFile().readText()
        assertTrue(text.contains("\"name\" : \"session\""))
        assertTrue(text.contains("\"domain\" : \"example.com\""))
        assertTrue(text.contains("\"value\" : \"abc123\""))
    }
}
