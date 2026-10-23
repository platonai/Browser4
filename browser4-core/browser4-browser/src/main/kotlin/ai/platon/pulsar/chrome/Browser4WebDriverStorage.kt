package ai.platon.pulsar.chrome

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Serialisable shape of the browser storage state exchanged by
 * [Browser4WebDriver.saveStorageState] and [Browser4WebDriver.loadStorageState].
 *
 * The DTOs mirror Playwright's storage-state JSON. They stay internal to the module: callers pass
 * and receive the JSON string.
 */



internal data class StorageStatePayload(
    val cookies: List<Map<String, Any?>> = emptyList(),
    val origins: List<StorageStateOriginPayload> = emptyList(),
)

internal data class StorageStateOriginPayload(
    val origin: String = "",
    val localStorage: List<StorageStateEntryPayload> = emptyList(),
)

internal data class StorageStateEntryPayload(
    val name: String = "",
    val value: String = "",
)

internal data class StorageStateLoadSummary(
    val cookies: Int,
    val origins: Int,
    val localStorageEntries: Int,
)
