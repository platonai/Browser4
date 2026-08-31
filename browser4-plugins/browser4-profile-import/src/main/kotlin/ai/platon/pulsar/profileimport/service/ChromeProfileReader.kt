package ai.platon.pulsar.profileimport.service

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Chrome/Edge profile metadata from the `Local State` JSON file and
 * resolves profile inputs (directory name or display name) to a concrete
 * profile directory, using the same three-tier matching as agent-browser:
 *
 * 1. exact directory name match
 * 2. case-insensitive display name match (error when ambiguous)
 * 3. case-insensitive directory name match
 */
open class ChromeProfileReader {

    companion object {
        private val logger = LoggerFactory.getLogger(ChromeProfileReader::class.java)
        private val objectMapper = pulsarObjectMapper()

        /**
         * Lists all profiles found in [userDataDir] by reading
         * `Local State` → `profile.info_cache`. Empty when missing/malformed.
         */
        fun listProfiles(userDataDir: Path, browser: String): List<SourceProfile> {
            val localState = userDataDir.resolve("Local State")
            if (!Files.isRegularFile(localState)) return emptyList()
            val infoCache: JsonNode = try {
                objectMapper.readTree(localState.toFile())
                    .path("profile").path("info_cache")
            } catch (e: Exception) {
                logger.warn("Failed to parse Local State at {}: {}", localState, e.message)
                return emptyList()
            }
            if (!infoCache.isObject) return emptyList()

            return infoCache.fields().asSequence().map { (dirName, info) ->
                val displayName = info.path("name").asText(dirName)
                SourceProfile(
                    browser = browser,
                    directory = dirName,
                    name = displayName,
                    userDataDir = userDataDir,
                    profileDir = userDataDir.resolve(dirName),
                )
            }.sortedBy { it.directory }.toList()
        }

        /**
         * Resolves [input] against the profiles in [userDataDir] using the
         * three-tier matching above. Throws [IllegalArgumentException] when no
         * profile matches or the display name is ambiguous.
         */
        fun resolveProfile(userDataDir: Path, browser: String, input: String): SourceProfile {
            val profiles = listProfiles(userDataDir, browser)
            if (profiles.isEmpty()) {
                throw IllegalArgumentException("No $browser profiles found in $userDataDir")
            }
            if (input.isEmpty()) return profiles.first()

            // Tier 1: exact directory name
            profiles.firstOrNull { it.directory == input }?.let { return it }

            val inputLower = input.lowercase()
            // Tier 2: case-insensitive display name
            val displayMatches = profiles.filter { it.name.lowercase() == inputLower }
            if (displayMatches.size == 1) return displayMatches.first()
            if (displayMatches.size > 1) {
                throw IllegalArgumentException(
                    "Ambiguous profile name \"$input\". Multiple profiles match: " +
                        displayMatches.joinToString(", ") { it.directory } +
                        ". Use the directory name instead."
                )
            }
            // Tier 3: case-insensitive directory name
            profiles.firstOrNull { it.directory.lowercase() == inputLower }?.let { return it }

            throw IllegalArgumentException(
                "Profile \"$input\" not found. Available profiles: " +
                    profiles.joinToString(", ") { "${it.directory} (${it.name})" }
            )
        }
    }
}
