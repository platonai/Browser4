package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for [LoadOptionDefaults] mutable state and [LoadOptionDefaults.reset].
 *
 * CRITICAL: This is the ONLY test class that mutates LoadOptionDefaults.
 * It uses explicit LoadOptionDefaults.reset() in @BeforeEach to start from
 * a known state, and restores in @AfterEach.
 *
 * Isolation: JUnit 5 runs classes sequentially by default. If parallel
 * execution is configured, this class should be annotated with @Isolated.
 */
class TestLoadOptionDefaults {

    private val conf = VolatileConfig.UNSAFE

    // Save all mutable state for restoration
    private var savedExpires: Duration = Duration.ZERO
    private var savedExpireAt: java.time.Instant = java.time.Instant.EPOCH
    private var savedLazyFlush = false
    private var savedParse = false
    private var savedStoreContent = false
    private var savedIgnoreFailure = false
    private var savedNJitRetry = 0
    private var savedTest = 0

    @BeforeEach
    fun setUp() {
        // Save current state before we modify anything
        savedExpires = LoadOptionDefaults.expires
        savedExpireAt = LoadOptionDefaults.expireAt
        savedLazyFlush = LoadOptionDefaults.lazyFlush
        savedParse = LoadOptionDefaults.parse
        savedStoreContent = LoadOptionDefaults.storeContent
        savedIgnoreFailure = LoadOptionDefaults.ignoreFailure
        savedNJitRetry = LoadOptionDefaults.nJitRetry
        savedTest = LoadOptionDefaults.test

        // Start from known baseline
        LoadOptionDefaults.reset()
    }

    @AfterEach
    fun tearDown() {
        // Restore original state
        LoadOptionDefaults.expires = savedExpires
        LoadOptionDefaults.expireAt = savedExpireAt
        LoadOptionDefaults.lazyFlush = savedLazyFlush
        LoadOptionDefaults.parse = savedParse
        LoadOptionDefaults.storeContent = savedStoreContent
        LoadOptionDefaults.ignoreFailure = savedIgnoreFailure
        LoadOptionDefaults.nJitRetry = savedNJitRetry
        LoadOptionDefaults.test = savedTest
    }

    // ---------------------------------------------------------------------------
    // reset()
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("reset: restores all vars to their constants")
    fun testResetRestoresAllVars() {
        // Mutate all vars
        LoadOptionDefaults.expires = Duration.ofDays(1)
        LoadOptionDefaults.expireAt = java.time.Instant.now()
        LoadOptionDefaults.lazyFlush = false
        LoadOptionDefaults.parse = true
        LoadOptionDefaults.storeContent = false
        LoadOptionDefaults.ignoreFailure = true
        LoadOptionDefaults.nJitRetry = 5
        LoadOptionDefaults.test = 99

        // Reset
        LoadOptionDefaults.reset()

        // Verify all restored
        assertEquals(LoadOptionDefaults.EXPIRES, LoadOptionDefaults.expires, "expires should reset")
        assertEquals(LoadOptionDefaults.EXPIRE_AT, LoadOptionDefaults.expireAt, "expireAt should reset")
        assertEquals(LoadOptionDefaults.LAZY_FLUSH, LoadOptionDefaults.lazyFlush, "lazyFlush should reset")
        assertEquals(LoadOptionDefaults.PARSE, LoadOptionDefaults.parse, "parse should reset")
        assertEquals(LoadOptionDefaults.STORE_CONTENT, LoadOptionDefaults.storeContent, "storeContent should reset")
        assertEquals(LoadOptionDefaults.IGNORE_FAILURE, LoadOptionDefaults.ignoreFailure, "ignoreFailure should reset")
        assertEquals(LoadOptionDefaults.N_JIT_RETRY, LoadOptionDefaults.nJitRetry, "nJitRetry should reset")
        assertEquals(LoadOptionDefaults.TEST, LoadOptionDefaults.test, "test should reset")
    }

    // ---------------------------------------------------------------------------
    // State isolation — changes propagate to new LoadOptions
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("change to defaults: new LoadOptions picks up changed defaults")
    fun testChangePropagatesToNewOptions() {
        val originalOptions = LoadOptions.create(conf)
        val originalExpires = originalOptions.expires

        // Change the global default
        LoadOptionDefaults.expires = Duration.ofHours(6)

        // New LoadOptions created after the change should use new default
        val newOptions = LoadOptions.create(conf)
        assertEquals(Duration.ofHours(6), newOptions.expires,
            "New LoadOptions should pick up changed default expires")
        assertNotEquals(originalExpires, newOptions.expires,
            "New default should differ from old default")
    }

    @Test
    @DisplayName("change to defaults: already-created options unaffected")
    fun testChangeDoesNotAffectExistingOptions() {
        val options = LoadOptions.create(conf)
        val originalExpires = options.expires

        // Change the global default
        LoadOptionDefaults.expires = Duration.ofHours(6)

        // Already-created options should retain their value
        assertEquals(originalExpires, options.expires,
            "Already-created options should not be affected by default change")
    }

    @Test
    @DisplayName("multiple defaults: changed independently")
    fun testMultipleFieldsChangedIndependently() {
        LoadOptionDefaults.parse = true
        LoadOptionDefaults.lazyFlush = false

        assertEquals(true, LoadOptionDefaults.parse, "parse should be changed")
        assertEquals(false, LoadOptionDefaults.lazyFlush, "lazyFlush should be changed")
        // Other fields should remain at reset values
        assertEquals(LoadOptionDefaults.EXPIRES, LoadOptionDefaults.expires, "expires should be unchanged")

        val options = LoadOptions.create(conf)
        assertNotEquals(LoadOptionDefaults.PARSE, options.parse,
            "New options should use changed parse default")
    }

    @Test
    @DisplayName("defaults: all 9 mutable vars have constant counterparts")
    fun testAllConstantsPresent() {
        // Verify the mapping between constants and vars
        assertEquals(LoadOptionDefaults.EXPIRES, LoadOptionDefaults.expires,
            "EXPIRES constant should match initial expires var")
        assertEquals(LoadOptionDefaults.EXPIRE_AT, LoadOptionDefaults.expireAt,
            "EXPIRE_AT constant should match initial expireAt var")
        assertEquals(LoadOptionDefaults.LAZY_FLUSH, LoadOptionDefaults.lazyFlush,
            "LAZY_FLUSH constant should match initial lazyFlush var")
        assertEquals(LoadOptionDefaults.PARSE, LoadOptionDefaults.parse,
            "PARSE constant should match initial parse var")
        assertEquals(LoadOptionDefaults.STORE_CONTENT, LoadOptionDefaults.storeContent,
            "STORE_CONTENT constant should match initial storeContent var")
        assertEquals(LoadOptionDefaults.IGNORE_FAILURE, LoadOptionDefaults.ignoreFailure,
            "IGNORE_FAILURE constant should match initial ignoreFailure var")
        assertEquals(LoadOptionDefaults.N_JIT_RETRY, LoadOptionDefaults.nJitRetry,
            "N_JIT_RETRY constant should match initial nJitRetry var")
        assertEquals(LoadOptionDefaults.TEST, LoadOptionDefaults.test,
            "TEST constant should match initial test var")
    }
}
