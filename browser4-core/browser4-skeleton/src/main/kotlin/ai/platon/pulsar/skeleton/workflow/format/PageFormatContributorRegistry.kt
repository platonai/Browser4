package ai.platon.pulsar.skeleton.workflow.format

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry of [PageFormatContributor]s, keyed by format id.
 *
 * `PluginManager` fills it at application startup from
 * `PageFormatContributorMount` beans; the format engine resolves a requested
 * format's contributor through [get]. Core formats have no entry here — they are
 * implemented inside the engine, and [register] refuses to accept their ids so a
 * plugin cannot shadow them.
 *
 * Registration is **first wins**: a contributor whose id is already registered is
 * skipped with a warning. Import order must never decide which implementation of
 * `branding` a deployment gets.
 *
 * There is no built-in registration in the constructor (unlike
 * `PageSummaryAlgorithmRegistry`, whose `wpsi` algorithm must always exist):
 * every contributor-provided format is optional by definition, and an empty
 * registry is the correct state for a stock install.
 */
class PageFormatContributorRegistry private constructor() {

    private val logger = getLogger(this)
    private val contributors = ConcurrentHashMap<String, PageFormatContributor>()

    /**
     * Register a contributor.
     *
     * @throws IllegalArgumentException when [PageFormatContributor.id] is blank,
     *   does not match [ID_PATTERN], or is reserved by a core/deprecated format.
     *   Wiring callers catch this and log it, so one bad plugin cannot abort startup.
     * @return true when registered; false when the id is already taken (the
     *   existing registration is kept).
     */
    fun register(contributor: PageFormatContributor): Boolean {
        val id = contributor.id
        require(PageFormats.isValidId(id)) {
            "Page format contributor id must match $ID_PATTERN, actual: '$id'"
        }
        require(!isReserved(id)) {
            "Page format id '$id' is reserved by the built-in engine and cannot be contributed"
        }
        // Two failures that used to surface only at call time — as a warning on a
        // response the caller had already paid for. Both are knowable here, so they are
        // refused here, where the message can still reach a plugin author.
        require(PageFormats.isContributed(id)) {
            "Page format id '$id' is not one of this build's contributed formats " +
                "(${PageFormats.CONTRIBUTED.sorted()}); the engine only offers a contributor an id it " +
                "knows, so this registration would never run"
        }
        require(contributor.outputField in PageFormats.contributedFields()) {
            "Page format '$id' writes '${contributor.outputField}', which is not a document field a " +
                "contributor may own; writable: ${PageFormats.contributedFields().sorted()}"
        }

        val previous = contributors.putIfAbsent(id, contributor)
        if (previous != null) {
            logger.warn(
                "Page format '{}' already contributed ({}); skipping {}",
                id, previous.javaClass.simpleName, contributor.javaClass.simpleName
            )
            return false
        }

        logger.info(
            "+ Registered page format contributor: '{}' ({})",
            id, contributor.javaClass.simpleName
        )
        return true
    }

    /** The contributor registered as [id], or null when the format is unclaimed. */
    fun get(id: String): PageFormatContributor? = contributors[id]

    /** True when some contributor claims [id]. */
    fun contains(id: String): Boolean = contributors.containsKey(id)

    /** All registered contributors, ordered by id for stable listings. */
    fun list(): List<PageFormatContributor> = contributors.values.sortedBy { it.id }

    /** All claimed format ids, ordered alphabetically. */
    fun availableIds(): List<String> = contributors.keys.sorted()

    /**
     * The registered contributors that report themselves unavailable right now,
     * so a capability listing can name every format that exists but cannot run.
     */
    fun unavailable(): List<PageFormatContributor> = list().filter { !it.isAvailable() }

    /**
     * Remove every registration. Intended for unit tests only — production code
     * never unregisters contributors.
     */
    fun clear() {
        contributors.clear()
    }

    /** Number of registered contributors. */
    fun size(): Int = contributors.size

    private fun isReserved(id: String): Boolean =
        id in PageFormats.CORE || id in PageFormats.DEPRECATED

    companion object {
        /** Human-readable contributor id syntax, used in error messages. */
        const val ID_PATTERN = PageFormats.ID_PATTERN

        /** Singleton registry shared by the format engine and plugin wiring. */
        val instance: PageFormatContributorRegistry by lazy { PageFormatContributorRegistry() }
    }
}
