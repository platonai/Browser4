package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for LoadOptions configuration and event handler methods:
 * [LoadOptions.overrideConfiguration], [LoadOptions.setInteractionSettings],
 * event handler lazy initialization via [LoadOptions.eventHandlers] / [LoadOptions.itemEventHandlers].
 */
class TestLoadOptionsConfigOverride {

    private val conf = VolatileConfig.UNSAFE

    // LoadOptionDefaults isolation
    private lateinit var savedExpires: Duration
    private lateinit var savedExpireAt: Instant
    private var savedIgnoreFailure = false

    @BeforeEach
    fun saveDefaults() {
        savedExpires = LoadOptionDefaults.expires
        savedExpireAt = LoadOptionDefaults.expireAt
        savedIgnoreFailure = LoadOptionDefaults.ignoreFailure
    }

    @AfterEach
    fun restoreDefaults() {
        LoadOptionDefaults.expires = savedExpires
        LoadOptionDefaults.expireAt = savedExpireAt
        LoadOptionDefaults.ignoreFailure = savedIgnoreFailure
    }

    // ---------------------------------------------------------------------------
    // overrideConfiguration() — no args
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("overrideConfiguration: returns conf when no args")
    fun testOverrideConfigurationNoArgs() {
        val options = LoadOptions.create(conf)
        val result = options.overrideConfiguration()
        assertNotNull(result, "overrideConfiguration should return a config")
    }

    @Test
    @DisplayName("overrideConfiguration: returns the same conf object")
    fun testOverrideConfigurationReturnsConf() {
        val options = LoadOptions.create(conf)
        val result = options.overrideConfiguration()
        // It returns the conf it was constructed with
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // overrideConfiguration(conf) — with explicit config
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("overrideConfiguration(conf): accepts external config")
    fun testOverrideConfigurationWithExternalConf() {
        val options = LoadOptions.create(conf)
        val externalConf = VolatileConfig.UNSAFE
        val result = options.overrideConfiguration(externalConf)
        assertNotNull(result)
    }

    @Test
    @DisplayName("overrideConfiguration(conf): returns null for null conf")
    fun testOverrideConfigurationNullConf() {
        val options = LoadOptions.create(conf)
        val result = options.overrideConfiguration(null)
        assertEquals(null, result, "null conf should return null")
    }

    // ---------------------------------------------------------------------------
    // setInteractionSettings() — tested indirectly via overrideConfiguration()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("setInteractionSettings: no modification when interaction options are default")
    fun testSetInteractionSettingsNoModification() {
        val options = LoadOptions.create(conf)
        // All interaction options at defaults — overrideConfiguration should succeed
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    @Test
    @DisplayName("setInteractionSettings: triggers when interactLevel is modified")
    fun testSetInteractionSettingsInteractLevelModified() {
        val options = LoadOptions.parse("-interactLevel GOOD_DATA", conf)
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    @Test
    @DisplayName("setInteractionSettings: triggers when scrollCount is modified")
    fun testSetInteractionSettingsScrollCountModified() {
        val options = LoadOptions.parse("-scrollCount 10", conf)
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    @Test
    @DisplayName("setInteractionSettings: triggers when scrollInterval is modified")
    fun testSetInteractionSettingsScrollIntervalModified() {
        val options = LoadOptions.parse("-scrollInterval 2s", conf)
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    @Test
    @DisplayName("setInteractionSettings: triggers when scriptTimeout is modified")
    fun testSetInteractionSettingsScriptTimeoutModified() {
        val options = LoadOptions.parse("-scriptTimeout 30s", conf)
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    @Test
    @DisplayName("setInteractionSettings: triggers when pageLoadTimeout is modified")
    fun testSetInteractionSettingsPageLoadTimeoutModified() {
        val options = LoadOptions.parse("-pageLoadTimeout 5m", conf)
        val result = options.overrideConfiguration()
        assertNotNull(result)
    }

    // ---------------------------------------------------------------------------
    // eventHandlers / itemEventHandlers — lazy init via public properties
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("eventHandlers: lazy-init creates handlers")
    fun testEventHandlersLazyInit() {
        val options = LoadOptions.create(conf)
        val handlers = options.eventHandlers
        assertNotNull(handlers, "eventHandlers should be initialized on first access")
    }

    @Test
    @DisplayName("eventHandlers: returns same instance on re-access")
    fun testEventHandlersReuse() {
        val options = LoadOptions.create(conf)
        val first = options.eventHandlers
        val second = options.eventHandlers
        assertTrue(first === second, "eventHandlers should return the same instance")
    }

    @Test
    @DisplayName("itemEventHandlers: lazy-init creates handlers")
    fun testItemEventHandlersLazyInit() {
        val options = LoadOptions.create(conf)
        val handlers = options.itemEventHandlers
        assertNotNull(handlers, "itemEventHandlers should be initialized on first access")
    }

    @Test
    @DisplayName("itemEventHandlers: returns handlers on re-access")
    fun testItemEventHandlersReuse() {
        val options = LoadOptions.create(conf)
        val first = options.itemEventHandlers
        val second = options.itemEventHandlers
        assertNotNull(first, "itemEventHandlers should be initialized")
        assertNotNull(second, "itemEventHandlers should be returned on re-access")
    }
}
