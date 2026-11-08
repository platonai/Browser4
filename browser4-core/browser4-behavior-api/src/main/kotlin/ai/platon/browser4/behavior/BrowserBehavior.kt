package ai.platon.browser4.behavior

import ai.platon.browser4.behavior.humanization.InteractionHumanizer
import ai.platon.browser4.behavior.humanization.NeutralInteractionHumanizer
import ai.platon.browser4.behavior.stealth.BrowserStealthProvider
import ai.platon.browser4.behavior.stealth.NeutralBrowserStealthProvider
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The registry that hands the driver and the launch funnel the active
 * [BrowserStealthProvider] and [InteractionHumanizer].
 *
 * ## Why a registry instead of injection
 *
 * The driver is constructed deep inside the browser library, on paths that do not go through
 * Spring (pooled launches, attached sessions, background fetches), and the launch funnel sits
 * in `browser4-protocol`, one module above the driver. A JVM-wide registry backed by
 * [ServiceLoader] is the only seam both layers can share without threading a context object
 * through the library. It keeps `browser4-browser` compiled against the SPI only: the default
 * implementation is a *runtime* dependency, so a professional or custom edition replaces it
 * on the classpath without touching the driver.
 *
 * ## Resolution order
 *
 * 1. The id configured in [B4Constants.BEHAVIOR_STEALTH_PROVIDER] /
 *    [B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER], matched case-insensitively against the
 *    installed providers. An unknown id is not fatal: the first provider in priority order is
 *    used and a warning names both ids once.
 * 2. Otherwise the installed provider with the highest
 *    [priority][BrowserStealthProvider.priority], ties broken by [id][BrowserStealthProvider.id]
 *    so the choice is stable across JVM runs.
 * 3. When no provider is installed, a loud warning is logged once and a neutral, fully
 *    functional fallback is used (see [NeutralBrowserStealthProvider] /
 *    [NeutralInteractionHumanizer]).
 *
 * The provider list is loaded once per JVM and cached. The classpath is normally fixed at
 * startup, but a host may install jars later (a `plugins/` directory): such a loader is added
 * with [registerProviderClassLoader], which drops the cache. [reset] does the same explicitly,
 * for tests and for a host that isolates runs.
 *
 * ## Configuration
 *
 * ```properties
 * # Pick a specific installed provider by id (empty = highest priority)
 * browser.behavior.stealth.provider=
 * browser.behavior.humanization.provider=
 * ```
 *
 * Both keys are ordinary browser configuration, so the usual sources apply:
 * `application.properties`, `-D` JVM options and the environment. See
 * `docs/browser-behavior-modules.md` for the module layout and how an edition is built.
 */
object BrowserBehavior {
    private val logger = getLogger(BrowserBehavior::class)

    private val lock = Any()

    /** One warning per (kind, requested id) pair, so a bad config cannot flood the log. */
    private val warnedCarriers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Loaders installed by [registerProviderClassLoader], e.g. a runtime `plugins/` directory. */
    private val extraClassLoaders: MutableSet<ClassLoader> = CopyOnWriteArraySet()

    @Volatile
    private var registry: Registry? = null

    /**
     * The stealth provider that applies to the browser configured by [config].
     *
     * @param config the browser configuration whose provider override is honoured; `null`
     *   uses the classpath order only. Pass `BrowserSettings.config` from the call site.
     */
    @JvmStatic
    @JvmOverloads
    fun stealth(config: ImmutableConfig? = null): BrowserStealthProvider {
        val providers = load().stealth
        val requested = configuredId(config, B4Constants.BEHAVIOR_STEALTH_PROVIDER)
        return select(providers, requested, "stealth", { it.id }) ?: NeutralBrowserStealthProvider
    }

    /**
     * The interaction humanizer that applies to the browser configured by [config].
     *
     * @param config the browser configuration whose provider override is honoured; `null`
     *   uses the classpath order only.
     */
    @JvmStatic
    @JvmOverloads
    fun humanizer(config: ImmutableConfig? = null): InteractionHumanizer {
        val providers = load().humanizers
        val requested = configuredId(config, B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER)
        return select(providers, requested, "humanization", { it.id }) ?: NeutralInteractionHumanizer
    }

    /**
     * Every installed stealth provider in resolution order (highest priority first).
     *
     * Diagnostics only: the active provider is [stealth]. Returns an empty list when no
     * implementation module is on the classpath.
     */
    @JvmStatic
    fun installedStealthProviders(): List<BrowserStealthProvider> = load().stealth

    /**
     * Every installed humanizer in resolution order (highest priority first).
     *
     * Diagnostics only: the active humanizer is [humanizer].
     */
    @JvmStatic
    fun installedHumanizers(): List<InteractionHumanizer> = load().humanizers

    /**
     * Drop the cached provider list and the warning latches.
     *
     * Called by the registry itself when a provider class loader is installed or removed, and by
     * tests (and by a host that reloads the classpath); production code otherwise never needs it,
     * because the installed providers do not change while the JVM runs.
     */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            registry = null
        }
        warnedCarriers.clear()
    }

    /**
     * Add [loader] to the provider lookup and drop the cached list.
     *
     * A host that installs jars after startup calls this when it wraps the classpath — the
     * `plugins/` directory in `browser4-boot` does — so discovery does not depend on which
     * thread happens to resolve the first driver. The loader is usually the same one that was
     * installed as the thread-context loader, but registering it explicitly makes the lookup
     * deterministic: the thread-context loader alone is not enough, because worker and request
     * threads may run with the parent loader.
     */
    @JvmStatic
    fun registerProviderClassLoader(loader: ClassLoader) {
        if (extraClassLoaders.add(loader)) {
            logger.info("Registered behavior provider class loader: {}", loader)
            reset()
        }
    }

    /**
     * Remove a loader added by [registerProviderClassLoader] and drop the cached list.
     *
     * Called when a plugin directory is unloaded; the providers it contributed disappear with it.
     */
    @JvmStatic
    fun unregisterProviderClassLoader(loader: ClassLoader) {
        if (extraClassLoaders.remove(loader)) {
            logger.info("Unregistered behavior provider class loader: {}", loader)
            reset()
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun configuredId(config: ImmutableConfig?, key: String): String? =
        config?.get(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Every class loader the provider lookup must consult, in lookup order.
     *
     * - this class's defining loader: the modules shipped with the product;
     * - the thread-context loader: jars a host added by wrapping it (a `plugins/` directory);
     * - loaders registered with [registerProviderClassLoader]: jars added by a host whose
     *   worker threads do not inherit the wrapping loader.
     */
    private fun providerClassLoaders(): List<ClassLoader> {
        val loaders = LinkedHashSet<ClassLoader>()
        BrowserBehavior::class.java.classLoader?.let { loaders += it }
        Thread.currentThread().contextClassLoader?.let { loaders += it }
        loaders += extraClassLoaders
        return loaders.toList()
    }

    private fun <T> select(
        providers: List<T>,
        requestedId: String?,
        kind: String,
        idOf: (T) -> String,
    ): T? {
        if (providers.isEmpty()) {
            return null
        }
        if (requestedId == null) {
            return providers.first()
        }

        providers.firstOrNull { idOf(it).equals(requestedId, ignoreCase = true) }?.let { return it }

        if (warnedCarriers.add("$kind:$requestedId")) {
            logger.warn(
                "Configured {} provider [{}] is not installed (installed: {}); using [{}] instead. " +
                    "Check browser.behavior.{}.provider or add the edition module to the classpath.",
                kind,
                requestedId,
                providers.joinToString(", ") { idOf(it) },
                idOf(providers.first()),
                kind,
            )
        }
        return providers.first()
    }

    private fun load(): Registry = registry ?: synchronized(lock) {
        registry ?: Registry(
            stealth = loadProviders(BrowserStealthProvider::class.java),
            humanizers = loadProviders(InteractionHumanizer::class.java),
        ).also {
            registry = it
            report(it)
        }
    }

    /**
     * Load one SPI type from every [providerClassLoaders] and merge the results.
     *
     * The same implementation is visible from several loaders (a wrapping plugin loader also
     * sees its parent's modules), so the merge dedupes by id — the id is what deployments pin,
     * and a duplicate would otherwise show up twice in diagnostics.
     */
    private fun <T : Any> loadProviders(type: Class<T>): List<T> {
        val providers = providerClassLoaders().flatMap { loadProviders(type, it) }

        // Highest priority first; the id tie-break keeps the order stable across class loaders.
        // `distinctBy` runs after the sort, so a duplicated id keeps its highest-priority
        // implementation (an edition that reuses the community id still wins).
        return providers
            .sortedWith(compareByDescending<T> { providerPriority(it) }.thenBy { providerId(it) })
            .distinctBy { providerId(it) }
    }

    /**
     * Load one SPI type from a single [loader], skipping a broken registration instead of
     * failing the JVM.
     *
     * A `META-INF/services` entry for a class that is missing or does not implement the
     * interface raises [ServiceConfigurationError] on iteration; an edition module that is
     * half-installed must degrade to "that provider is absent", not break browser startup.
     */
    private fun <T : Any> loadProviders(type: Class<T>, loader: ClassLoader): List<T> {
        val serviceLoader = ServiceLoader.load(type, loader)
        val providers = mutableListOf<T>()
        val iterator = serviceLoader.iterator()
        while (true) {
            val hasNext = try {
                iterator.hasNext()
            } catch (e: ServiceConfigurationError) {
                logger.warn("Broken {} service registration: {}", type.simpleName, e.message)
                false
            }
            if (!hasNext) {
                break
            }
            val provider = try {
                iterator.next()
            } catch (e: ServiceConfigurationError) {
                logger.warn("Broken {} service registration: {}", type.simpleName, e.message)
                continue
            } catch (e: Exception) {
                logger.warn("Could not instantiate a {} provider: {}", type.simpleName, e.message)
                continue
            }
            providers += provider
        }
        return providers
    }

    private fun providerId(provider: Any): String = when (provider) {
        is BrowserStealthProvider -> provider.id
        is InteractionHumanizer -> provider.id
        else -> provider::class.java.name
    }

    private fun providerPriority(provider: Any): Int = when (provider) {
        is BrowserStealthProvider -> provider.priority
        is InteractionHumanizer -> provider.priority
        else -> 0
    }

    /**
     * Announce the active implementation once, and warn only when the installed set is
     * surprising (nothing installed, or several implementations competing).
     */
    private fun report(loaded: Registry) {
        val stealth = loaded.stealth.firstOrNull()
        val humanizer = loaded.humanizers.firstOrNull()

        if (stealth == null) {
            logger.warn(
                "No BrowserStealthProvider is installed; browser stealth falls back to the base " +
                    "library defaults. Add browser4-behavior-default (or an edition module) to the classpath."
            )
        }
        if (humanizer == null) {
            logger.warn(
                "No InteractionHumanizer is installed; interactions use upstream timing only. " +
                    "Add browser4-behavior-default (or an edition module) to the classpath."
            )
        }
        if (stealth != null || humanizer != null) {
            logger.info(
                "Browser behavior: stealth={} [{}], humanization={} [{}]",
                stealth?.id ?: "none",
                stealth?.edition ?: "-",
                humanizer?.id ?: "none",
                humanizer?.edition ?: "-",
            )
        }
        if (loaded.stealth.size > 1) {
            // Only the "edition overrides a still-installed community module" layout is worth a
            // warning: the default module also ships the baseline page-world payload, whose
            // classpath shadowing depends on module order, so shipping both can silently keep
            // the community payload. Several providers of the *same* edition are a deliberate
            // priority setup and are reported by the info line above.
            val community = loaded.stealth.filter {
                it.edition == BrowserBehaviorEdition.DEFAULT && it.id != stealth?.id
            }
            if (stealth != null && stealth.edition != BrowserBehaviorEdition.DEFAULT && community.isNotEmpty()) {
                logger.warn(
                    "BrowserStealthProvider [{}] (edition {}) is active, but the community default " +
                        "implementation is still on the classpath: {}. Exclude browser4-behavior-default " +
                        "so exactly one stealth module ships — otherwise the default module's " +
                        "js/stealth.js payload can win the classpath lookup.",
                    stealth.id,
                    stealth.edition,
                    community.joinToString(", ") { it.id },
                )
            }
        }
        if (loaded.humanizers.size > 1) {
            val community = loaded.humanizers.filter {
                it.edition == BrowserBehaviorEdition.DEFAULT && it.id != humanizer?.id
            }
            if (humanizer != null && humanizer.edition != BrowserBehaviorEdition.DEFAULT && community.isNotEmpty()) {
                logger.warn(
                    "InteractionHumanizer [{}] (edition {}) is active, but the community default " +
                        "implementation is still on the classpath: {}. Exclude browser4-behavior-default " +
                        "so exactly one humanization module ships.",
                    humanizer.id,
                    humanizer.edition,
                    community.joinToString(", ") { it.id },
                )
            }
        }
    }

    private class Registry(
        val stealth: List<BrowserStealthProvider>,
        val humanizers: List<InteractionHumanizer>,
    )
}
