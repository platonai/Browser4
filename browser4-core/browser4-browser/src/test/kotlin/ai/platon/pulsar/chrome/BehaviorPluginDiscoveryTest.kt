package ai.platon.pulsar.chrome

import ai.platon.browser4.behavior.BrowserBehavior
import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.humanization.ClickPointRequest
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.DragPlan
import ai.platon.browser4.behavior.humanization.DragRequest
import ai.platon.browser4.behavior.humanization.InteractionHumanizer
import ai.platon.browser4.behavior.humanization.PointerMovePlan
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import ai.platon.browser4.behavior.humanization.PointerPosition
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * A host may install behavior SPI providers after startup: the `plugins/` directory in
 * `browser4-boot` wraps the classpath in a `URLClassLoader` and registers it with
 * [BrowserBehavior].
 *
 * These tests prove the registry consults such loaders and that removing one takes its
 * providers away again. A registry that only ever asks its own defining loader would silently
 * ignore every edition jar, and the failure would surface as "the plugin is on disk but the
 * provider never activates" — which is exactly what [BrowserBehavior.humanizer] would report
 * as a fallback instead of an error.
 */
@DisplayName("Behavior provider discovery through a plugin class loader")
@Tag("Unit")
@Tag("Fast")
class BehaviorPluginDiscoveryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var pluginLoader: URLClassLoader
    private var originalContextLoader: ClassLoader? = null

    @BeforeEach
    fun setUp() {
        originalContextLoader = Thread.currentThread().contextClassLoader
        pluginLoader = URLClassLoader(arrayOf(pluginJar().toUri().toURL()), originalContextLoader)
        BrowserBehavior.reset()
    }

    @AfterEach
    fun tearDown() {
        BrowserBehavior.unregisterProviderClassLoader(pluginLoader)
        BrowserBehavior.reset()
        Thread.currentThread().contextClassLoader = originalContextLoader
        pluginLoader.close()
    }

    @Test
    @DisplayName("a provider inside a plugin jar stays invisible until its loader is installed")
    fun pluginProviderIsInvisibleUntilItsLoaderIsInstalled() {
        assertFalse(PLUGIN_ID in installedIds(), "the jar must not be discovered on its own")

        BrowserBehavior.registerProviderClassLoader(pluginLoader)

        assertTrue(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")
        // The provider must also be selectable by id, not merely present in diagnostics.
        assertEquals(PLUGIN_ID, BrowserBehavior.humanizer(configPinningPlugin()).id)
    }

    @Test
    @DisplayName("the thread-context loader is consulted without an explicit registration")
    fun threadContextLoaderIsConsulted() {
        Thread.currentThread().contextClassLoader = pluginLoader
        BrowserBehavior.reset()

        assertTrue(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")
    }

    @Test
    @DisplayName("unregistering the loader takes its provider away again")
    fun unregisteringTheLoaderRemovesTheProvider() {
        BrowserBehavior.registerProviderClassLoader(pluginLoader)
        assertTrue(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")

        BrowserBehavior.unregisterProviderClassLoader(pluginLoader)

        assertFalse(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")
    }

    @Test
    @DisplayName("merging the loaders reports each provider exactly once")
    fun mergedLookupReportsEachProviderOnce() {
        // The plugin loader is parent-first, so it also sees the community modules the test
        // classpath carries. The merge must dedupe them by id instead of listing them twice
        // (and a duplicate would make the id tie-break pick an arbitrary instance).
        Thread.currentThread().contextClassLoader = pluginLoader
        BrowserBehavior.registerProviderClassLoader(pluginLoader)
        BrowserBehavior.reset()

        val ids = installedIds()

        assertEquals(ids.distinct(), ids, "duplicate provider ids: $ids")
        assertTrue("default" in ids, "installed: $ids")
        assertTrue(PLUGIN_ID in ids, "installed: $ids")
    }

    // ---- Helpers ----

    private fun installedIds(): List<String> = BrowserBehavior.installedHumanizers().map { it.id }

    private fun configPinningPlugin(): ImmutableConfig =
        MutableConfig(loadDefaults = true).apply {
            set(B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER, PLUGIN_ID)
        }

    /** A jar that carries only the SPI registration, standing in for an edition module. */
    private fun pluginJar(): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        val jarPath = tempDir.resolve("edition-humanizer.jar")
        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jos ->
            jos.putNextEntry(JarEntry(PROVIDER_CLASS_ENTRY))
            jos.write(providerClassBytes())
            jos.closeEntry()
            jos.putNextEntry(JarEntry(SERVICES_ENTRY))
            jos.write(PluginTestHumanizer::class.java.name.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarPath
    }

    private fun providerClassBytes(): ByteArray =
        PluginTestHumanizer::class.java.getResourceAsStream("/$PROVIDER_CLASS_ENTRY")
            ?.use { it.readBytes() }
            ?: error("$PROVIDER_CLASS_ENTRY is missing from the test classpath")

    private companion object {
        const val PLUGIN_ID = "plugin-test"
        const val PROVIDER_CLASS_ENTRY = "ai/platon/pulsar/chrome/PluginTestHumanizer.class"
        const val SERVICES_ENTRY =
            "META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer"
    }
}

/**
 * The provider packed into the jar by [BehaviorPluginDiscoveryTest].
 *
 * The class sits on the test classpath only so the test can copy its bytes; the jar carries the
 * `META-INF/services` registration, which is what discovery keys on. It outranks the community
 * default ([priority] 1) so a test can prove the plugin provider is the one that gets selected.
 */
class PluginTestHumanizer : InteractionHumanizer {
    override val id: String = "plugin-test"

    override val edition: String = BrowserBehaviorEdition.CUSTOM

    override val priority: Int = 1

    override fun pointerMove(request: PointerMoveRequest, delays: DelaySource): PointerMovePlan =
        PointerMovePlan(steps = 2, delayMillis = 0)

    override fun clickPoint(request: ClickPointRequest): PointerPosition =
        PointerPosition(request.x, request.y)

    override fun pressHoldMillis(delays: DelaySource): Long = 0

    override fun multiClickGapMillis(delays: DelaySource): Long = 0

    override fun keyPressDelayMillis(delays: DelaySource): Long = 0

    override fun typeCharDelayMillis(codePoint: String, delays: DelaySource): Long = 0

    override fun dragPlan(request: DragRequest, delays: DelaySource): DragPlan = DragPlan(
        sourceX = request.sourceX,
        sourceY = request.sourceY,
        targetX = request.targetX,
        targetY = request.targetY,
        delays = List(4) { 0L },
    )
}
