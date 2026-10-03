package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry of [PageSummaryAlgorithm]s used by the `htmlsnapshot summary`
 * command.
 *
 * The built-in [WpsiPageSummaryAlgorithm] ([DEFAULT_ID]) is registered when the
 * registry is created, so the command always works even with no plugin
 * installed. Plugin algorithms are registered at application startup by
 * `PluginManager` when it wires `PageSummaryAlgorithmMount` beans.
 *
 * Registration is first-wins: an algorithm whose id is already registered is
 * skipped with a warning. This keeps a plugin JAR from silently replacing the
 * built-in algorithm (or another plugin's algorithm); callers that need to
 * test alternative implementations can [clear] the registry in tests.
 *
 * The default algorithm can be overridden at runtime via the Spring property
 * [CONFIG_KEY_DEFAULT_ALGORITHM] (applied by `PluginManager` at startup). If the
 * property points to an unregistered id, [resolve] without an explicit id
 * returns null, so callers fail fast with an error listing the available ids
 * instead of silently producing a different summary than configured.
 */
class PageSummaryAlgorithmRegistry private constructor() {

    private val logger = getLogger(this)
    private val algorithms = ConcurrentHashMap<String, PageSummaryAlgorithm>()

    /** The default algorithm id returned by [resolve] when no explicit id is
     * given. Mutable so that a Spring configuration value can override the
     * built-in default at startup. */
    @Volatile
    private var defaultId: String = DEFAULT_ID

    init {
        register(WpsiPageSummaryAlgorithm)
    }

    /** The currently effective default algorithm id. */
    fun defaultId(): String = defaultId

    /**
     * Override the default algorithm id.
     *
     * The id must already be registered when this is called — `PluginManager`
     * applies the configured value after all plugin mounts are wired. Setting
     * an unregistered id is accepted but logged as a warning; [resolve] without
     * an explicit id then returns null so the caller fails fast with the list
     * of available ids.
     *
     * @throws IllegalArgumentException when [id] is blank or does not match
     * the id syntax.
     */
    fun setDefaultId(id: String) {
        require(ID_REGEX.matches(id)) {
            "Default summary algorithm id must match $ID_PATTERN, actual: '$id'"
        }
        defaultId = id
        if (get(id) == null) {
            logger.warn(
                "Default summary algorithm is set to '{}' but no algorithm with " +
                    "that id is registered. Available: {}", id, availableIds().joinToString(", ")
            )
        } else {
            logger.info("Default summary algorithm overridden to '{}'", id)
        }
    }

    /**
     * Register an algorithm.
     *
     * @throws IllegalArgumentException when [PageSummaryAlgorithm.id] is blank
     * or does not match the id syntax.
     * @return true when registered; false when an algorithm with the same id
     * already exists (the existing registration is kept).
     */
    fun register(algorithm: PageSummaryAlgorithm): Boolean {
        val id = algorithm.id
        require(ID_REGEX.matches(id)) {
            "Summary algorithm id must match $ID_PATTERN, actual: '$id'"
        }

        val previous = algorithms.putIfAbsent(id, algorithm)
        if (previous != null) {
            logger.warn(
                "Summary algorithm '{}' already registered ({}); skipping {}",
                id, previous.javaClass.simpleName, algorithm.javaClass.simpleName
            )
            return false
        }

        logger.info(
            "+ Registered page summary algorithm: '{}' ({})",
            id, algorithm.javaClass.simpleName
        )
        return true
    }

    /** Get the algorithm registered as [id], or null when absent. */
    fun get(id: String): PageSummaryAlgorithm? = algorithms[id]

    /** All registered algorithms, ordered by id for stable listings. */
    fun list(): List<PageSummaryAlgorithm> = algorithms.values.sortedBy { it.id }

    /** All registered algorithm ids, ordered alphabetically. */
    fun availableIds(): List<String> = algorithms.keys.sorted()

    /**
     * Resolve the algorithm to use for a call.
     *
     * @param idOrNull the requested id; null/blank means the effective default
     *   ([defaultId], which is [DEFAULT_ID] unless overridden via
     *   [setDefaultId] / [CONFIG_KEY_DEFAULT_ALGORITHM]).
     * @return the algorithm, or null when the requested (or configured default)
     *   id is not registered.
     */
    fun resolve(idOrNull: String?): PageSummaryAlgorithm? {
        val id = idOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return algorithms[defaultId]
        return algorithms[id]
    }

    /**
     * Remove all registered algorithms, including the built-in one, and reset
     * the default id override. Intended for unit tests only — production code
     * never unregisters algorithms.
     */
    fun clear() {
        algorithms.clear()
        defaultId = DEFAULT_ID
    }

    /** Number of registered algorithms. */
    fun size(): Int = algorithms.size

    companion object {
        /** Id of the built-in Web Page Summary Index algorithm. */
        const val DEFAULT_ID = "wpsi"

        /**
         * Spring property that overrides the default summary algorithm, e.g.
         * `browser4.htmlsnapshot.summary.algorithm=my-algorithm`. Applied by
         * `PluginManager` after all plugin algorithms are registered.
         */
        const val CONFIG_KEY_DEFAULT_ALGORITHM = "browser4.htmlsnapshot.summary.algorithm"

        /** Human-readable id syntax, used in error messages. */
        const val ID_PATTERN = "[a-z0-9][a-z0-9-]*"

        private val ID_REGEX = Regex(ID_PATTERN)

        /** Singleton registry shared by the MCP layer and plugin wiring. */
        val instance: PageSummaryAlgorithmRegistry by lazy { PageSummaryAlgorithmRegistry() }
    }
}
