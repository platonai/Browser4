package ai.platon.browser4.importer.writer

import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedBookmark
import ai.platon.pulsar.browser.BrowserProfile
import ai.platon.pulsar.browser.common.ProfilePaths
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Writes imported browser data into a Browser4-managed Chrome profile.
 *
 * The target Chrome profile's data is stored under:
 * - `<contextDir>/<browserType>/Default/Bookmarks` for normal profiles
 * - `ProfilePaths.PROTOTYPE_DATA_DIR/Default/Bookmarks` for prototype profiles
 *
 * Bookmarks are merged into the existing Bookmarks JSON under a
 * "Imported from <Browser>" folder in the "Other bookmarks" root.
 */
class ChromeProfileWriter(private val profile: BrowserProfile) {

    companion object {
        private val logger = LoggerFactory.getLogger(ChromeProfileWriter::class.java)

        /** Chrome epoch: 1601-01-01T00:00:00Z. Timestamps are microseconds since this instant. */
        private val CHROME_EPOCH = Instant.parse("1601-01-01T00:00:00Z")
    }

    /**
     * Write imported bookmarks into the target profile's Bookmarks JSON file.
     *
     * Creates a new "Imported from <source>" folder under "Other bookmarks" and
     * adds all bookmarks there. If the Bookmarks file does not exist, a minimal
     * valid Chrome Bookmarks structure is created.
     *
     * @param bookmarks the bookmarks to write
     * @param source    which browser they came from (used in folder name)
     * @return the number of bookmarks actually written
     */
    fun writeBookmarks(bookmarks: List<ImportedBookmark>, source: ImportSource): Int {
        if (bookmarks.isEmpty()) return 0

        val bookmarksPath = bookmarksPath()

        // Read existing Bookmarks JSON, or create a minimal valid structure
        val rootNode = if (Files.isRegularFile(bookmarksPath)) {
            try {
                pulsarObjectMapper().readTree(bookmarksPath.toFile())
            } catch (e: Exception) {
                logger.warn("Failed to read existing Bookmarks file, creating new one | {}", e.message)
                createMinimalBookmarksRoot()
            }
        } else {
            createMinimalBookmarksRoot()
        }

        // Ensure we have an ObjectNode to mutate
        val root = if (rootNode is ObjectNode) rootNode else {
            logger.warn("Bookmarks file root is not an object, creating new structure")
            createMinimalBookmarksRoot()
        }

        // Build the imported folder
        val targetFolderName = "${source.displayName} bookmarks"  // e.g. "Google Chrome bookmarks"
        val importedFolder = buildImportedFolder(bookmarks, targetFolderName)

        // Merge into "other" root (or create it)
        val rootsNode = root.get("roots")
        val roots = if (rootsNode is ObjectNode) rootsNode else {
            logger.warn("Bookmarks JSON missing 'roots' object, creating new structure")
            val newRoots = createMinimalBookmarksRoot().get("roots") as ObjectNode
            root.set<ObjectNode>("roots", newRoots)
            newRoots
        }

        val otherNode = roots.get("other")
        val otherRoot = if (otherNode is ObjectNode) otherNode else createRootFolder("Other bookmarks")
        val existingChildren = if (otherRoot.get("children") is ArrayNode) {
            otherRoot.get("children") as ArrayNode
        } else {
            pulsarObjectMapper().createArrayNode()
        }

        existingChildren.add(importedFolder)
        otherRoot.set<ArrayNode>("children", existingChildren)
        otherRoot.put("date_modified", chromeTimeNow())
        roots.set<ObjectNode>("other", otherRoot)

        // Update root checksum/version
        root.put("version", 1)

        // Write back
        createParentDirs(bookmarksPath)
        pulsarObjectMapper().writerWithDefaultPrettyPrinter().writeValue(bookmarksPath.toFile(), root)

        logger.info("Wrote {} bookmarks to {} under '{}'", bookmarks.size, bookmarksPath, targetFolderName)
        return bookmarks.size
    }

    // -- Private helpers --

    /**
     * Resolve the path to the Bookmarks JSON file for this profile.
     *
     * Matches the path resolution logic in [ai.platon.pulsar.browser.BrowserId.userDataDir]:
     * - System default: placeholder (treated as non-importable)
     * - Prototype: `ProfilePaths.PROTOTYPE_DATA_DIR`
     * - Others: `contextDir/<browserTypeName>`
     */
    private fun defaultProfileDir(): Path {
        return when {
            profile.isSystemDefault -> AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER
            profile.isPrototype -> ProfilePaths.PROTOTYPE_DATA_DIR
            else -> profile.contextDir.resolve(profile.fingerprint.browserType.name)
        }.resolve("Default")
    }

    private fun bookmarksPath(): Path = defaultProfileDir().resolve("Bookmarks")

    /**
     * Create a minimal valid Chrome Bookmarks JSON structure with empty root folders.
     */
    private fun createMinimalBookmarksRoot(): ObjectNode {
        val mapper = pulsarObjectMapper()
        val root = mapper.createObjectNode()
        val roots = mapper.createObjectNode()

        roots.set<ObjectNode>("bookmark_bar", createRootFolder("Bookmarks bar"))
        roots.set<ObjectNode>("other", createRootFolder("Other bookmarks"))
        roots.set<ObjectNode>("synced", createRootFolder("Mobile bookmarks"))

        root.set<ObjectNode>("roots", roots)
        root.put("version", 1)
        return root
    }

    /** Create a top-level root folder node (empty children). */
    private fun createRootFolder(name: String): ObjectNode {
        val mapper = pulsarObjectMapper()
        val now = chromeTimeNow()
        return mapper.createObjectNode().apply {
            put("type", "folder")
            put("name", name)
            set<ArrayNode>("children", mapper.createArrayNode())
            put("date_added", now)
            put("date_modified", now)
        }
    }

    /**
     * Build a folder node containing the given bookmarks as children.
     */
    private fun buildImportedFolder(bookmarks: List<ImportedBookmark>, folderName: String): ObjectNode {
        val mapper = pulsarObjectMapper()
        val now = chromeTimeNow()
        val childrenArr = mapper.createArrayNode()

        for (bm in bookmarks) {
            val child = mapper.createObjectNode()
            child.put("type", "url")
            child.put("name", bm.title)
            child.put("url", bm.url)
            child.put("date_added", if (bm.dateAdded > 0) bm.dateAdded else now)
            childrenArr.add(child)
        }

        return mapper.createObjectNode().apply {
            put("type", "folder")
            put("name", folderName)
            put("date_added", now)
            put("date_modified", now)
            set<ArrayNode>("children", childrenArr)
        }
    }

    /**
     * Current time as a Chrome timestamp (microseconds since 1601-01-01 UTC).
     *
     * Uses millisecond precision to avoid Long overflow — converting multi-century
     * durations directly to nanoseconds overflows a signed 64-bit long.
     */
    private fun chromeTimeNow(): Long {
        val now = Instant.now()
        val millis = Duration.between(CHROME_EPOCH, now).toMillis()
        return millis * 1000  // millis → micros
    }

    private fun createParentDirs(path: Path) {
        path.parent?.let { Files.createDirectories(it) }
    }
}
