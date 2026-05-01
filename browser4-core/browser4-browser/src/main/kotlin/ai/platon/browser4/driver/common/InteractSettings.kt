package ai.platon.browser4.driver.common

import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.core.JacksonException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class DomSettlePolicy {
    READY_STATE_INTERACTIVE,
    READY_STATE_COMPLETE,
    NETWORK_IDLE,
    FIELDS_SETTLE,
    HASH,
    OTHER
}

enum class DelayPreset {
    FAST,
    DEFAULT,
    STEALTH
}

/**
 * The interaction settings.
 * */
data class InteractSettings constructor(
    /**
     * Page positions to scroll to, these numbers are percentages of the total height,
     * e.g., 0.2 means to scroll to 20% of the height of the page.
     *
     * Some typical positions are:
     * * 0.3,0.75,0.4,0.5
     * * 0.2, 0.3, 0.5, 0.75, 0.5, 0.4, 0.5, 0.75
     * 0.3,0.75,0.4,0.5
     * */
    var initScrollPositions: String = "0.3,0.75",
    /**
     * The number of scroll downs on the page.
     * */
    var autoScrollCount: Int = 1,
    /**
     * The time interval to scroll down on the page.
     * */
    var scrollInterval: Duration = Duration.ofMillis(500),
    /**
     * Timeout for executing custom scripts on the page.
     * */
    var scriptTimeout: Duration = Duration.ofMinutes(1),
    /**
     * Timeout for loading a webpage by session.load().
     * */
    var pageLoadTimeout: Duration = Duration.ofMinutes(3),
    /**
     * Whether to bring the page to the front before scroll.
     * */
    var bringToFront: Boolean = false,
    /**
     * DOM settle policy.
     * TODO: the default value will be set to NETWORK_IDLE
     * */
    var domSettlePolicy: DomSettlePolicy = DomSettlePolicy.FIELDS_SETTLE
) {
    /**
     * The minimum delay time in milliseconds.
     * */
    var minDelayMillis = 50

    /**
     * The minimum delay time in milliseconds.
     * */
    var maxDelayMillis = 2000

    /**
     * Delay buckets used by interaction actions.
     *
     * - Unit: milliseconds (`ms`).
     * - Value type: [IntRange], inclusive bounds (`first..last`).
     * - Resolution order in runtime: specific `action` key -> `default` -> caller fallback.
     * - The empty key `""` is kept as a compatibility fallback and is normalized to `default`.
     *
     * Known keys:
     * - `gap`: general pacing between high-level actions/retries.
     * - `click`: mouse down/up gap for click operations.
     * - `delete`: delay used while clearing text (delete/backspace/shortcut presses).
     * - `keyUpDown`: reserved bucket for key-up/key-down style actions.
     * - `press`: key hold duration for single key press actions.
     * - `type`: inter-character delay when typing text.
     * - `fill`: post-fill pause after paste-like input.
     * - `mouseWheel`: delay between consecutive wheel ticks.
     * - `dragAndDrop`: delay inside drag-and-drop sequence.
     * - `waitForNavigation`: polling interval while waiting for URL change.
     * - `waitForSelector`: polling interval while waiting for selector existence.
     * - `waitUntil`: generic polling interval bucket.
     * - `default`: primary fallback bucket for unknown/missing keys.
     * - `""`: secondary compatibility fallback bucket.
     * */
    val delayPolicy = mutableMapOf(
        "gap" to 650..1100, // Used by AbstractWebDriver.gap(): pacing between high-level actions and retry loops.
        "click" to 90..180, // Used by PulsarWebDriver.click(): Mouse.click delay between mousedown/mouseup (and between multi-click cycles).
        "delete" to 80..180, // Used by PulsarWebDriver.clear(): per delete/backspace or press("Control+A"/"Delete") key press delay.
        "keyUpDown" to 70..170, // Reserved key-up/key-down bucket; currently not directly referenced by PulsarWebDriver actions.
        "press" to 120..260, // Used by PulsarWebDriver.press(): key hold duration passed to Keyboard.press (which enforces >= 60 ms).
        "type" to 90..240, // Used by PulsarWebDriver.type(): inter-character delay passed to Keyboard.type.
        "fill" to 130..280, // Used by PulsarWebDriver.fill(): post-fill pause via gap("fill") after paste-like typing.
        "mouseWheel" to 180..420, // Used by mouseWheelDown/mouseWheelUp: delay between repeated wheel ticks.
        "dragAndDrop" to 260..650, // Used by dragAndDrop(): delay between dragOver and drop in Mouse.dragAndDrop.
        "waitForNavigation" to 250..700, // Used by waitUntil("waitForNavigation", ...): polling interval while waiting URL change.
        "waitForSelector" to 200..600, // Used by waitUntil("waitForSelector", ...): polling interval while waiting element existence.
        "waitUntil" to 220..650, // Intended generic waitUntil polling bucket; current default overload uses key "waitUtil" and falls back.
        "default" to 200..600, // Primary fallback for unknown/missing action keys in randomDelayMillis(action).
        "" to 200..600 // Secondary fallback key, normalized to the same value as "default".
    )

    private val defaultDelayPolicyTemplate = delayPolicy.toMap()


    /**
     * Apply a predefined delay profile for different crawling scenarios.
     * */
    fun applyDelayPreset(preset: DelayPreset): InteractSettings {
        val presetPolicy = when (preset) {
            DelayPreset.FAST -> mapOf(
                "gap" to 350..700,
                "click" to 60..120,
                "delete" to 50..120,
                "keyUpDown" to 50..120,
                "press" to 90..180,
                "type" to 60..160,
                "fill" to 80..180,
                "mouseWheel" to 120..260,
                "dragAndDrop" to 180..420,
                "waitForNavigation" to 120..380,
                "waitForSelector" to 100..320,
                "waitUntil" to 120..360,
                "default" to 120..320,
                "" to 120..320
            )
            DelayPreset.DEFAULT -> defaultDelayPolicyTemplate
            DelayPreset.STEALTH -> mapOf(
                "gap" to 900..1600,
                "click" to 120..260,
                "delete" to 110..260,
                "keyUpDown" to 90..220,
                "press" to 180..360,
                "type" to 140..320,
                "fill" to 220..450,
                "mouseWheel" to 260..700,
                "dragAndDrop" to 420..1100,
                "waitForNavigation" to 350..1100,
                "waitForSelector" to 280..900,
                "waitUntil" to 300..900,
                "default" to 280..900,
                "" to 280..900
            )
        }

        delayPolicy.putAll(presetPolicy)
        generateRestrictedDelayPolicy()
        return this
    }

    /**
     * The minimum delay time in milliseconds.
     * */
    var minTimeout = Duration.ofSeconds(1)

    /**
     * The minimum delay time in milliseconds.
     * */
    var maxTimeout = Duration.ofMinutes(3)

    /**
     * Timeout policy for each action in seconds.
     * */
    var timeoutPolicy = mutableMapOf(
        "pageLoad" to pageLoadTimeout,
        "script" to scriptTimeout,
        "waitForNavigation" to Duration.ofSeconds(60),
        "waitForSelector" to Duration.ofSeconds(60),
        "waitUntil" to Duration.ofSeconds(60),
        "default" to Duration.ofSeconds(60),
        "" to Duration.ofSeconds(60)
    )

    /**
     * The delay policy for each action.
     * The delay policy is a map from action to a range of delay time in milliseconds.
     *
     * The map should contain the following keys:
     * * gap
     * * click
     * * delete
     * * keyUpDown
     * * press
     * * type
     * * mouseWheel
     * * dragAndDrop
     * * waitForNavigation
     * * waitForSelector
     * * waitUntil
     * * default
     * * ""(empty key)
     *
     * @return a map from action to a range of delay time in milliseconds.
     * */
    fun generateRestrictedDelayPolicy(): Map<String, IntRange> {
        val fallback = (minDelayMillis..maxDelayMillis)

        delayPolicy.forEach { (action, delay) ->
            if (delay.first < minDelayMillis) {
                delayPolicy[action] = minDelayMillis..delay.last.coerceAtLeast(minDelayMillis)
            } else if (delay.last > maxDelayMillis) {
                delayPolicy[action] = delay.first.coerceAtMost(maxDelayMillis)..maxDelayMillis
            }
        }

        delayPolicy["default"] = delayPolicy["default"] ?: fallback
        delayPolicy[""] = delayPolicy["default"] ?: fallback

        return delayPolicy
    }

    /**
     * Timeout policy for each action.
     *
     * The map should contain the following keys:
     * * waitForNavigation
     * * waitForSelector
     * * waitUntil
     * * default
     * * ""(empty key)
     *
     * @return a map from action to a range of delay time in milliseconds.
     * */
    fun generateRestrictedTimeoutPolicy(): Map<String, Duration> {
        val fallback = Duration.ofSeconds(60)

        timeoutPolicy.forEach { (action, timeout) ->
            if (timeout < minTimeout) {
                timeoutPolicy[action] = minTimeout
            } else if (timeout > maxTimeout) {
                timeoutPolicy[action] = maxTimeout
            }
        }

        timeoutPolicy["default"] = timeoutPolicy["default"] ?: fallback
        timeoutPolicy[""] = timeoutPolicy["default"] ?: fallback

        return timeoutPolicy
    }

    /**
     * Build scroll positions
     * */
    fun buildScrollPositions(): List<Double> {
        val positions = buildInitScrollPositions().toMutableList()

        if (autoScrollCount <= 0) {
            return positions
        }

        val scrollCount = autoScrollCount
        val random = Random.nextInt(3)
        val enhancedScrollCount = (scrollCount + random - 1).coerceAtLeast(1)
        repeat(enhancedScrollCount) { i ->
            val ratio = (0.6 + 0.1 * i).coerceAtMost(0.8)
            positions.add(ratio)
        }

        return positions
    }

    fun overrideSystemProperties(): InteractSettings {
        Systems.setProperty(CapabilityTypes.BROWSER_INTERACT_SETTINGS, toJson())

        Systems.setProperty(CapabilityTypes.FETCH_SCROLL_DOWN_COUNT, autoScrollCount)
        Systems.setProperty(CapabilityTypes.FETCH_SCROLL_DOWN_INTERVAL, scrollInterval)
        Systems.setProperty(CapabilityTypes.FETCH_SCRIPT_TIMEOUT, scriptTimeout)
        Systems.setProperty(CapabilityTypes.FETCH_PAGE_LOAD_TIMEOUT, pageLoadTimeout)

        return this
    }

    fun overrideConfiguration(conf: MutableConfig): InteractSettings {
        conf[CapabilityTypes.BROWSER_INTERACT_SETTINGS] = toJson()

        conf.setInt(CapabilityTypes.FETCH_SCROLL_DOWN_COUNT, autoScrollCount)
        conf.setDuration(CapabilityTypes.FETCH_SCROLL_DOWN_INTERVAL, scrollInterval)
        conf.setDuration(CapabilityTypes.FETCH_SCRIPT_TIMEOUT, scriptTimeout)
        conf.setDuration(CapabilityTypes.FETCH_PAGE_LOAD_TIMEOUT, pageLoadTimeout)

        return this
    }

    /**
     * Do not scroll the page by default.
     * */
    fun noScroll(): InteractSettings {
        initScrollPositions = ""
        autoScrollCount = 0
        return this
    }

    /**
     * Build the initial scroll positions.
     * */
    fun buildInitScrollPositions(): List<Double> {
        if (initScrollPositions.isBlank()) {
            return listOf()
        }

        return initScrollPositions.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    }

    /**
     * Convert the object to a json string.
     *
     * @return a json string
     * */
    @Throws(JacksonException::class)
    fun toJson(): String {
        return pulsarObjectMapper().writeValueAsString(this)
    }

    object Builder {

        /**
         * Interaction behavior to visit pages at fastest speed.
         * */
        val FASTEST
            get() = InteractSettings(
                scrollInterval = Duration.ofMillis(500),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(2),
                bringToFront = false
            ).noScroll()

        /**
         * Interaction behavior to visit pages at faster speed.
         * */
        val FASTER
            get() = InteractSettings(
                autoScrollCount = 0,
                scrollInterval = Duration.ofMillis(500),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(2),
                bringToFront = false,
                initScrollPositions = "0.2"
            )

        /**
         * Interaction behavior to visit pages at faster speed.
         * */
        val FAST
            get() = InteractSettings(
                autoScrollCount = 0,
                scrollInterval = Duration.ofMillis(500),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(2),
                bringToFront = false,
                initScrollPositions = "0.2,0.5"
            )

        /**
         * Default interaction behavior.
         * */
        val DEFAULT get() = InteractSettings()

        /**
         * Interaction behavior for good data.
         * */
        val GOOD_DATA
            get() = InteractSettings(
                autoScrollCount = 2,
                scrollInterval = Duration.ofSeconds(1),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(3),
                bringToFront = true,
                initScrollPositions = "0.3,0.75,0.4,0.5"
            )

        /**
         * Interaction behavior for better data.
         * */
        val BETTER_DATA
            get() = InteractSettings(
                autoScrollCount = 3,
                scrollInterval = Duration.ofSeconds(1),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(3),
                bringToFront = true,
                initScrollPositions = "0.3,0.75,0.4,0.5"
            )

        /**
         * Interaction behavior for best data.
         * */
        val BEST_DATA
            get() = InteractSettings(
                autoScrollCount = 5,
                scrollInterval = Duration.ofSeconds(1),
                scriptTimeout = Duration.ofSeconds(30),
                pageLoadTimeout = Duration.ofMinutes(3),
                bringToFront = true,
                initScrollPositions = "0.3,0.75,0.3,0.5,0.75"
            )
    }

    companion object {
        private val OBJECT_CACHE = ConcurrentHashMap<String, InteractSettings>()

        /**
         * Default interaction behavior.
         * */
        val DEFAULT get() = Builder.DEFAULT

        /**
         * Create interaction settings by [InteractLevel] and apply the corresponding delay preset.
         *
         * Mapping:
         * - FASTEST/FASTER/FAST -> [DelayPreset.FAST]
         * - DEFAULT -> [DelayPreset.DEFAULT]
         * - GOOD_DATA/BETTER_DATA/BEST_DATA -> [DelayPreset.STEALTH]
         *
         * The factory first builds a level-specific baseline from [Builder], then applies the preset
         * via [applyDelayPreset] so timing policy stays consistent with the chosen interaction level.
         */
        fun create(level: InteractLevel): InteractSettings {
            val settings = when (level) {
                InteractLevel.FASTEST -> Builder.FASTEST
                InteractLevel.FASTER -> Builder.FASTER
                InteractLevel.FAST -> Builder.FAST
                InteractLevel.DEFAULT -> Builder.DEFAULT
                InteractLevel.GOOD_DATA -> Builder.GOOD_DATA
                InteractLevel.BETTER_DATA -> Builder.BETTER_DATA
                InteractLevel.BEST_DATA -> Builder.BEST_DATA
            }

            val preset = when (level) {
                InteractLevel.FASTEST,
                InteractLevel.FASTER,
                InteractLevel.FAST -> DelayPreset.FAST

                InteractLevel.DEFAULT -> DelayPreset.DEFAULT

                InteractLevel.GOOD_DATA,
                InteractLevel.BETTER_DATA,
                InteractLevel.BEST_DATA -> DelayPreset.STEALTH
            }

            return settings.applyDelayPreset(preset)
        }

        /**
         * Parse the json string to an InteractSettings object.
         *
         * @param json the json string
         * @return an InteractSettings object
         * */
        @Throws(JacksonException::class)
        fun fromJson(json: String): InteractSettings {
            return OBJECT_CACHE.computeIfAbsent(json) {
                pulsarObjectMapper().readValue(json, InteractSettings::class.java)
            }
        }

        /**
         * Parse the json string to an InteractSettings object.
         *
         * @param json the json string
         * @param defaultValue the default value
         * @return an InteractSettings object
         * */
        fun fromJson(json: String?, defaultValue: InteractSettings): InteractSettings {
            return fromJsonOrNull(json) ?: defaultValue
        }

        /**
         * Parse the json string to an InteractSettings object.
         *
         * @param json the json string
         * @return an InteractSettings object, or null if the json string is null, or the json string is invalid
         * */
        fun fromJsonOrNull(json: String?): InteractSettings? = json?.runCatching { fromJson(json) }?.getOrNull()
    }
}
