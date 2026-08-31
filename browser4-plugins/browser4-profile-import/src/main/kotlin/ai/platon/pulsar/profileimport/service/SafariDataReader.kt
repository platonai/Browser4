package ai.platon.pulsar.profileimport.service

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSObject
import com.dd.plist.PropertyListParser
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Safari data readers and converters.
 *
 * Safari stores data in its own formats, none of which are Chromium-native:
 * - Bookmarks: `Bookmarks.plist` (binary or XML plist)
 * - Cookies: `Cookies.binarycookies` (binary, unencrypted)
 * - History: `History.db` (SQLite) — not converted in v1
 * - Passwords: the login Keychain — never read programmatically
 *
 * Bookmarks are converted into a Chrome `Bookmarks` JSON file; cookies are
 * converted into a JSON array compatible with Browser4's `state-load` /
 * `cookies set` JSON format.
 */
open class SafariDataReader {

    companion object {
        private val logger = LoggerFactory.getLogger(SafariDataReader::class.java)
        private val objectMapper = pulsarObjectMapper()

        /** Unix seconds for 2001-01-01T00:00:00Z (mac absolute time epoch). */
        private const val MAC_ABSOLUTE_EPOCH_OFFSET = 978_307_200L

        /** ms between 1601-01-01 (Chrome internal epoch) and 1970-01-01. */
        private const val CHROME_EPOCH_OFFSET_MS = 116_444_736_00000L
    }

    data class SafariBookmark(
        val title: String,
        val url: String?,
        val children: MutableList<SafariBookmark> = mutableListOf(),
    )

    data class SafariCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expires: Long?, // unix seconds; null = session cookie
        val secure: Boolean,
        val httpOnly: Boolean,
    )

    // ------------------------------------------------------------------
    // Bookmarks: plist -> Chrome Bookmarks JSON
    // ------------------------------------------------------------------

    /** Parses `Bookmarks.plist` into a bookmark tree. */
    fun parseBookmarks(plistFile: Path): List<SafariBookmark> {
        val root = PropertyListParser.parse(plistFile.toFile()) as? NSDictionary
            ?: throw IllegalArgumentException("Not a plist dictionary: $plistFile")
        val children = root.get("Children") as? NSArray ?: return emptyList()
        return (0 until children.count()).mapNotNull { i -> convertNode(children.objectAtIndex(i)) }
    }

    private fun convertNode(node: NSObject?): SafariBookmark? {
        val dict = node as? NSDictionary ?: return null
        val type = dict.get("WebBookmarkType")?.toString() ?: return null
        val title = dict.get("WebBookmarkTitle")?.toString() ?: ""
        val children = (dict.get("Children") as? NSArray)
            ?.let { arr -> (0 until arr.count()).mapNotNull { j -> convertNode(arr.objectAtIndex(j)) } }
            ?: emptyList()
        return SafariBookmark(
            title = title,
            url = dict.get("URLString")?.toString(),
            children = children.toMutableList(),
        )
    }

    /**
     * Writes a Chrome-format `Bookmarks` JSON file to [dest] from the parsed
     * Safari bookmarks. Folders become `bookmark_bar` children, leaves become
     * URL nodes.
     */
    fun writeChromeBookmarks(bookmarks: List<SafariBookmark>, dest: Path) {
        val now = chromeTimeNow()
        var nextId = 2L

        fun node(b: SafariBookmark): Map<String, Any?> {
            val id = (nextId++).toString()
            val base = linkedMapOf<String, Any?>(
                "date_added" to now,
                "date_last_used" to "0",
                "guid" to UUID.randomUUID().toString().replace("-", "").uppercase(),
                "id" to id,
                "name" to b.title,
            )
            return if (b.url != null && b.children.isEmpty()) {
                base + mapOf("type" to "url", "url" to b.url)
            } else {
                base + mapOf(
                    "type" to "folder",
                    "children" to b.children.map { node(it) },
                )
            }
        }

        fun root(name: String, id: String, items: List<SafariBookmark>): Map<String, Any?> = linkedMapOf(
            "children" to items.map { node(it) },
            "date_added" to now,
            "date_last_used" to "0",
            "guid" to UUID.randomUUID().toString().replace("-", "").uppercase(),
            "id" to id,
            "name" to name,
            "type" to "folder",
        )

        val json = linkedMapOf<String, Any?>(
            "checksum" to "",
            "roots" to linkedMapOf(
                "bookmark_bar" to root("Bookmarks bar", "1", bookmarks),
                "other" to root("Other bookmarks", "2", emptyList()),
                "synced" to root("Mobile bookmarks", "3", emptyList()),
            ),
            "version" to 1,
        )
        Files.createDirectories(dest.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dest.toFile(), json)
    }

    /** Chrome internal time: microseconds since 1601-01-01. */
    private fun chromeTimeNow(): String =
        ((System.currentTimeMillis() + CHROME_EPOCH_OFFSET_MS) * 1000).toString()

    // ------------------------------------------------------------------
    // Cookies: Cookies.binarycookies -> JSON
    // ------------------------------------------------------------------

    /**
     * Parses a `Cookies.binarycookies` file. The format is binary but NOT
     * encrypted. See https://github.com/kawakatz/macCookies for the layout.
     */
    fun parseCookies(file: Path): List<SafariCookie> {
        val bytes = Files.readAllBytes(file)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.remaining() < 8) return emptyList()
        val magic = ByteArray(4)
        buf.get(magic)
        if (String(magic) != "cook") {
            throw IllegalArgumentException("not a binarycookies file: $file")
        }
        val numPages = buf.int
        if (numPages < 0 || numPages > 1024) {
            throw IllegalArgumentException("Suspicious page count $numPages in $file")
        }
        val pageSizes = IntArray(numPages) { buf.int }
        val cookies = mutableListOf<SafariCookie>()
        for (size in pageSizes) {
            if (size <= 0) continue
            val page = ByteBuffer.wrap(bytes, buf.position(), size).order(ByteOrder.BIG_ENDIAN)
            buf.position(buf.position() + size)
            cookies += parsePage(page, file)
        }
        return cookies
    }

    private fun parsePage(page: ByteBuffer, file: Path): List<SafariCookie> {
        // Cookie offsets inside a page are relative to the page start, but
        // `remaining()` shrinks as we read the header — track the page base.
        val pageBase = page.position()
        if (page.limit() - pageBase < 12) return emptyList()
        page.int // page header (0x00000100)
        val numCookies = page.int
        if (numCookies < 0 || numCookies > 4096) {
            logger.warn("Suspicious cookie count {} in {}", numCookies, file)
            return emptyList()
        }
        val offsets = IntArray(numCookies + 1) { page.int }
        val pageLength = page.limit() - pageBase
        return (0 until numCookies).mapNotNull { i ->
            val start = offsets[i]
            val end = offsets[i + 1]
            if (start < 0 || end <= start || end > pageLength) return@mapNotNull null
            parseCookie(slice(page, pageBase + start, end - start))
        }
    }

    private fun parseCookie(c: ByteBuffer): SafariCookie? {
        // Fixed struct: 4x4 (size/unknown/flags/unknown) + 2x8 (creation/expiry)
        // + 4x4 (domain/name/path/value offsets) + 6x4 (unknown/comment/unknowns)
        // = 72 bytes, followed by null-terminated domain/name/path/value strings.
        if (c.remaining() < 72) return null
        c.int // size
        c.int // unknown (0)
        val flags = c.int
        c.int // unknown
        c.long // creation (mac absolute)
        val expiry = c.long
        val domainOffset = c.int
        val nameOffset = c.int
        val pathOffset = c.int
        val valueOffset = c.int
        c.int // unknown
        c.int // comment offset
        c.int // comment length
        c.int // unknown
        c.int // unknown
        c.int // unknown

        fun stringAt(offset: Int): String {
            if (offset < 0 || offset >= c.limit()) return ""
            val data = ByteArray(c.limit() - offset)
            c.position(offset)
            c.get(data)
            return String(data, Charsets.UTF_8).substringBefore('\u0000')
        }

        return SafariCookie(
            name = stringAt(nameOffset),
            value = stringAt(valueOffset),
            domain = stringAt(domainOffset).removePrefix("."),
            path = stringAt(pathOffset).ifEmpty { "/" },
            expires = if (expiry > 0) expiry + MAC_ABSOLUTE_EPOCH_OFFSET else null,
            // bit 0 = secure, bit 2 = httpOnly (per macCookies mapping)
            secure = flags and 0x01 != 0,
            httpOnly = flags and 0x04 != 0,
        )
    }

    private fun slice(buf: ByteBuffer, start: Int, len: Int): ByteBuffer {
        val copy = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN)
        val saved = buf.position()
        buf.position(start)
        val arr = ByteArray(len)
        buf.get(arr)
        buf.position(saved)
        copy.put(arr)
        copy.flip()
        return copy
    }

    /**
     * Writes cookies as a JSON array of cookie objects, compatible with the
     * JSON accepted by Browser4's `cookies set --curl` and `state-load`.
     */
    fun writeCookiesJson(cookies: List<SafariCookie>, dest: Path) {
        val json = cookies.map { c ->
            linkedMapOf<String, Any?>(
                "name" to c.name,
                "value" to c.value,
                "domain" to c.domain,
                "path" to c.path,
                "secure" to c.secure,
                "httpOnly" to c.httpOnly,
                "expires" to c.expires?.let { Instant.ofEpochSecond(it).toString() },
                "expirationDate" to c.expires?.toDouble(),
            )
        }
        Files.createDirectories(dest.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dest.toFile(), json)
    }
}
