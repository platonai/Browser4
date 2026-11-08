package ai.platon.pulsar.boot.plugin

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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * [PluginClasspathEnhancer] is the product's entry point for an edition jar: it wraps the
 * classpath in a `URLClassLoader` before Spring starts. This test walks that path end to end —
 * a jar in `plugins/` contributes a behavior SPI provider, and closing the enhancer takes the
 * provider away again.
 *
 * Without the registration in [PluginClasspathEnhancer.enhance] the jar is on the classpath but
 * [BrowserBehavior] never looks at that loader, so the provider is silently absent.
 */
@DisplayName("Behavior providers contributed by a plugins/ jar")
@Tag("Unit")
@Tag("Fast")
class PluginBehaviorDiscoveryTest {

    @TempDir
    lateinit var tempDir: Path

    private var originalContextLoader: ClassLoader? = null

    @AfterEach
    fun tearDown() {
        PluginClasspathEnhancer.close()
        Thread.currentThread().contextClassLoader = originalContextLoader
        BrowserBehavior.reset()
    }

    @Test
    @DisplayName("enhance discovers the humanizer and close removes it")
    fun pluginJarContributesAHumanizer() {
        val pluginsDir = tempDir.resolve("plugins")
        Files.createDirectories(pluginsDir)
        writeHumanizerJar(pluginsDir.resolve("edition-humanizer.jar"))

        originalContextLoader = Thread.currentThread().contextClassLoader
        BrowserBehavior.reset()
        assertFalse(PLUGIN_ID in installedIds(), "the jar is not on the classpath yet")

        PluginClasspathEnhancer.enhance(pluginsDir)

        assertTrue(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")

        PluginClasspathEnhancer.close()

        assertFalse(PLUGIN_ID in installedIds(), "installed: ${installedIds()}")
    }

    // ---- Helpers ----

    private fun installedIds(): List<String> = BrowserBehavior.installedHumanizers().map { it.id }

    /** A jar with no plugin manifest: loaded as-is, so only the SPI entry matters here. */
    private fun writeHumanizerJar(jarPath: Path) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jos ->
            jos.putNextEntry(JarEntry(PROVIDER_CLASS_ENTRY))
            jos.write(providerClassBytes())
            jos.closeEntry()
            jos.putNextEntry(JarEntry(SERVICES_ENTRY))
            jos.write(EnhancerTestHumanizer::class.java.name.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
    }

    private fun providerClassBytes(): ByteArray =
        EnhancerTestHumanizer::class.java.getResourceAsStream("/$PROVIDER_CLASS_ENTRY")
            ?.use { it.readBytes() }
            ?: error("$PROVIDER_CLASS_ENTRY is missing from the test classpath")

    private companion object {
        const val PLUGIN_ID = "enhancer-test"
        const val PROVIDER_CLASS_ENTRY = "ai/platon/pulsar/boot/plugin/EnhancerTestHumanizer.class"
        const val SERVICES_ENTRY =
            "META-INF/services/ai.platon.browser4.behavior.humanization.InteractionHumanizer"
    }
}

/**
 * The provider packed into the plugins/ jar by [PluginBehaviorDiscoveryTest].
 *
 * The class sits on the test classpath only so the test can copy its bytes; discovery keys on
 * the `META-INF/services` entry inside the jar.
 */
class EnhancerTestHumanizer : InteractionHumanizer {
    override val id: String = "enhancer-test"

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
