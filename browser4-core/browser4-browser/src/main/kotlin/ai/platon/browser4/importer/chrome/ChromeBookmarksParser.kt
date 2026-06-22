package ai.platon.browser4.importer.chrome

import ai.platon.browser4.importer.ImportException
import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedBookmark
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses bookmarks from a Chrome/Chromium Bookmarks JSON file.
 *
 * The Chrome Bookmarks format is a JSON tree with roots:
 * ```
 * {
 *   "roots": {
 *     "bookmark_bar": { "type": "folder", "name": "Bookmarks bar", "children": [...] },
 *     "other":        { "type": "folder", "name": "Other bookmarks", "children": [...] },
 *     "synced":       { "type": "folder", "name": "Mobile bookmarks", "children": [...] }
 *   },
 *   "version": 1
 * }
 * ```
 *
 * Each node has `type` ("url" or "folder"), `name`, optionally `url`, `date_added`, and `children`.
 */
class ChromeBookmarksParser {

    companion object {
        private val logger = LoggerFactory.getLogger(ChromeBookmarksParser::class.java)

        /** Root folder JSON keys that should not appear in the imported folder path. */
        private val ROOT_FOLDER_KEYS = setOf("bookmark_bar", "other", "synced")
        /** Root folder display names (from the JSON "name" field) also excluded from path. */
        private val ROOT_DISPLAY_NAMES = setOf("Bookmarks bar", "Other bookmarks", "Mobile bookmarks")

        /**
         * Parse all bookmarks from a Chrome/Chromium [bookmarksFile].
         *
         * @return list of [ImportedBookmark], may be empty if the file has no bookmarks.
         * @throws ImportException if the file is corrupt or unreadable.
         */
        fun parse(bookmarksFile: Path): List<ImportedBookmark> {
            if (!Files.isRegularFile(bookmarksFile)) {
                logger.warn("Bookmarks file not found: {}", bookmarksFile)
                return emptyList()
            }

            return try {
                val root = pulsarObjectMapper().readTree(bookmarksFile.toFile())
                val roots = root.get("roots")
                if (roots == null || !roots.isObject) {
                    logger.warn("No 'roots' object in bookmarks file: {}", bookmarksFile)
                    return emptyList()
                }

                val bookmarks = mutableListOf<ImportedBookmark>()
                roots.fieldNames().forEachRemaining { folderName ->
                    val folderNode = roots.get(folderName)
                    if (folderNode != null && folderNode.isObject) {
                        walkBookmarks(folderNode, rootFolderLabel(folderName), bookmarks)
                    }
                }
                logger.info("Parsed {} bookmarks from {}", bookmarks.size, bookmarksFile)
                bookmarks
            } catch (e: ImportException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to parse Chrome bookmarks from {} | {}", bookmarksFile, e.message)
                throw ImportException("Failed to parse Chrome bookmarks: ${e.message}", e)
            }
        }

        // -- Internal --

        /**
         * Walk a bookmark tree node recursively, accumulating [ImportedBookmark] entries.
         *
         * @param node       the current JSON node
         * @param folderPath the human-readable folder path so far (e.g. "Bookmarks bar/Dev")
         * @param result     mutable list to accumulate results into
         */
        private fun walkBookmarks(
            node: JsonNode,
            folderPath: String,
            result: MutableList<ImportedBookmark>
        ) {
            val type = node.get("type")?.asText() ?: return
            val name = node.get("name")?.asText() ?: ""

            when (type) {
                "url" -> {
                    val url = node.get("url")?.asText()
                    if (!url.isNullOrBlank()) {
                        result.add(
                            ImportedBookmark(
                                title = name,
                                url = url,
                                folder = folderPath,
                                dateAdded = node.get("date_added")?.asLong() ?: 0L
                            )
                        )
                    }
                }

                "folder" -> {
                    val children = node.get("children")
                    if (children != null && children.isArray) {
                        // Only append the folder name if it is meaningful
                        val childPath = if (name.isNotBlank() && !isRootFolderName(name)) {
                            "$folderPath/$name"
                        } else {
                            folderPath
                        }
                        children.forEach { child -> walkBookmarks(child, childPath, result) }
                    }
                }
            }
        }

        /**
         * Root folder names from "roots" (bookmark_bar, other, synced) are mapped to
         * friendlier display labels; anything else is passed through unchanged.
         */
        private fun rootFolderLabel(key: String): String {
            return when (key) {
                "bookmark_bar" -> "Bookmarks bar"
                "other" -> "Other bookmarks"
                "synced" -> "Mobile bookmarks"
                else -> key
            }
        }

        /** True if [name] is one of the root folder keys or display names. */
        private fun isRootFolderName(name: String): Boolean {
            return name in ROOT_FOLDER_KEYS || name in ROOT_DISPLAY_NAMES
        }
    }
}
