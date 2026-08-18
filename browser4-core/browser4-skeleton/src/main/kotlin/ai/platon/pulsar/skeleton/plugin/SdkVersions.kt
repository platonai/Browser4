package ai.platon.pulsar.skeleton.plugin

/**
 * Minimal semantic-version parsing for Browser4 SDK and plugin versions.
 *
 * Supports the repo's version format `X.Y.Z(-qualifier)` (e.g. `4.14.0`,
 * `4.13.6-SNAPSHOT`, `4.12.0-rc.1`). It intentionally implements only what
 * the plugin compatibility policy needs — major/minor/patch parsing and
 * pre-release detection — rather than full SemVer ordering.
 */
object SdkVersions {

    private val VERSION_PATTERN = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:[-+]([0-9A-Za-z.\-]+))?""")

    /**
     * Parsed version components. [patch] defaults to 0 when absent,
     * [qualifier] is the `-qualifier` suffix (e.g. `SNAPSHOT`, `rc.1`) or null.
     */
    data class Parts(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val qualifier: String?,
    )

    /**
     * Parses a version string, or null when it is blank, not a plausible
     * `X.Y.Z(-qualifier)` version, or its numeric components overflow Int
     * (e.g. a mangled "99999999999999999999.0.0").
     */
    fun parse(version: String?): Parts? {
        if (version.isNullOrBlank()) return null
        val match = VERSION_PATTERN.matchEntire(version.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patchGroup = match.groupValues[3]
        val patch = when {
            patchGroup.isBlank() -> 0
            else -> patchGroup.toIntOrNull() ?: return null
        }
        return Parts(
            major = major,
            minor = minor,
            patch = patch,
            qualifier = match.groupValues[4].takeIf { it.isNotBlank() },
        )
    }

    /** The major component of [version], or null when unparseable. */
    fun majorOf(version: String?): Int? = parse(version)?.major

    /** Whether [version] is a pre-release such as `-SNAPSHOT` or `-rc.1`. */
    fun isSnapshot(version: String?): Boolean =
        parse(version)?.qualifier?.contains("snapshot", ignoreCase = true) ?: false
}
