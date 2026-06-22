package ai.platon.browser4.importer

import ai.platon.browser4.importer.model.ImportSource

/**
 * Base exception for import failures.
 */
open class ImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The requested browser is not installed or has no accessible profile data.
 */
class BrowserNotInstalledException(source: ImportSource) :
    ImportException("${source.displayName} is not installed or no profile data was found")

/**
 * A SQLite database is locked (browser is running).
 */
class DatabaseLockedException(source: ImportSource, dbName: String) :
    ImportException("${source.displayName} database '$dbName' is locked — close the browser first and try again")

/**
 * The data file is corrupt or unreadable.
 */
class CorruptDataException(source: ImportSource, detail: String, cause: Throwable? = null) :
    ImportException("Corrupt data from ${source.displayName}: $detail", cause)
