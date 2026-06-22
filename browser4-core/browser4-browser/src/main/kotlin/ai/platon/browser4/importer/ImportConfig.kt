package ai.platon.browser4.importer

import ai.platon.browser4.importer.model.ImportSource

/**
 * Configuration options for the browser data import process.
 */
data class ImportOptions(
    /** Whether to import bookmarks. */
    val importBookmarks: Boolean = true,
    /** Whether to import cookies (requires the source browser to be closed). */
    val importCookies: Boolean = false,
    /** Whether to import browsing history (future phase). */
    val importHistory: Boolean = false,
    /** Whether to import saved passwords (future phase). */
    val importPasswords: Boolean = false,
    /** Which source browsers to import from. Defaults to all available. */
    val sourceBrowsers: Set<ImportSource> = ImportSource.entries.toSet(),
    /** Target folder name within Chrome's "Other bookmarks". */
    val mergeBookmarksIntoFolderPrefix: String = "Imported from",
    /** If true, append to existing bookmarks; if false, replace the imported folder. */
    val appendToExistingBookmarks: Boolean = true,
    /** If true, skip bookmarks whose URL already exists in the target profile. */
    val deduplicateBookmarks: Boolean = true
)
