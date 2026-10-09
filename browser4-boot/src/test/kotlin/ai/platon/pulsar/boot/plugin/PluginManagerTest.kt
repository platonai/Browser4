package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.PageFormatContributorMount
import ai.platon.pulsar.skeleton.plugin.PageSummaryAlgorithmMount
import ai.platon.pulsar.skeleton.plugin.PluginMount
import ai.platon.pulsar.skeleton.workflow.format.FormatContext
import ai.platon.pulsar.skeleton.workflow.format.FormatInput
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributor
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributorRegistry
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithmRegistry
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryInput
import ai.platon.pulsar.skeleton.workflow.parse.html.WpsiPageSummaryAlgorithm
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.boot.ApplicationArguments
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Tests for [manifestOfLocation] — resolving a plugin manifest
 * from a class code-source URL. Guards against the regression where a
 * root/empty code source crashed application startup with a
 * NullPointerException (Path.getFileName() is null for root paths).
 */
class PluginManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val algorithmRegistry = PageSummaryAlgorithmRegistry.instance
    private val formatRegistry = PageFormatContributorRegistry.instance

    @BeforeEach
    fun resetAlgorithmRegistry() {
        algorithmRegistry.clear()
        algorithmRegistry.register(WpsiPageSummaryAlgorithm)
        formatRegistry.clear()
    }

    @AfterEach
    fun restoreAlgorithmRegistry() {
        algorithmRegistry.clear()
        algorithmRegistry.register(WpsiPageSummaryAlgorithm)
        formatRegistry.clear()
    }

    @Test
    @DisplayName("PageSummaryAlgorithmMount contributes algorithms into the registry on startup")
    fun wiresPageSummaryAlgorithmMount() {
        val extra = object : PageSummaryAlgorithm {
            override val id = "boot-test-algo"
            override val displayName = "Boot Test Algorithm"
            override val description = "contributed by PluginManagerTest"
            override fun generate(input: PageSummaryInput): String = "boot-test"
        }
        val mount = object : PageSummaryAlgorithmMount {
            override fun getPageSummaryAlgorithms(): List<PageSummaryAlgorithm> = listOf(extra)
        }

        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)

        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.environment).thenReturn(environment)
        Mockito.`when`(context.getBeansOfType(PluginMount::class.java))
            .thenReturn(mapOf("testMount" to mount))
        Mockito.`when`(context.getBeansOfType(Browser4Plugin::class.java))
            .thenReturn(emptyMap())

        PluginManager(context).run(Mockito.mock(ApplicationArguments::class.java))

        assertEquals(2, algorithmRegistry.size())
        assertSame(extra, algorithmRegistry.get("boot-test-algo"))
        assertSame(
            WpsiPageSummaryAlgorithm,
            algorithmRegistry.get(PageSummaryAlgorithmRegistry.DEFAULT_ID)
        )
    }

    @Test
    @DisplayName("configured default summary algorithm is applied after mounts are wired")
    fun appliesConfiguredDefaultAlgorithm() {
        val extra = object : PageSummaryAlgorithm {
            override val id = "plugin-default"
            override val displayName = "Plugin Default"
            override val description = "selected via browser4.htmlsnapshot.summary.algorithm"
            override fun generate(input: PageSummaryInput): String = "plugin-default"
        }
        val mount = object : PageSummaryAlgorithmMount {
            override fun getPageSummaryAlgorithms(): List<PageSummaryAlgorithm> = listOf(extra)
        }

        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)
        Mockito.`when`(
            environment.getProperty(PageSummaryAlgorithmRegistry.CONFIG_KEY_DEFAULT_ALGORITHM)
        ).thenReturn("plugin-default")

        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.environment).thenReturn(environment)
        Mockito.`when`(context.getBeansOfType(PluginMount::class.java))
            .thenReturn(mapOf("testMount" to mount))
        Mockito.`when`(context.getBeansOfType(Browser4Plugin::class.java))
            .thenReturn(emptyMap())

        PluginManager(context).run(Mockito.mock(ApplicationArguments::class.java))

        assertEquals("plugin-default", algorithmRegistry.defaultId())
        assertSame(extra, algorithmRegistry.resolve(null))
    }

    @Test
    @DisplayName("absent config property keeps wpsi as the default algorithm")
    fun absentConfigKeepsWpsiDefault() {
        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)

        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.environment).thenReturn(environment)
        Mockito.`when`(context.getBeansOfType(PluginMount::class.java)).thenReturn(emptyMap())
        Mockito.`when`(context.getBeansOfType(Browser4Plugin::class.java)).thenReturn(emptyMap())

        PluginManager(context).run(Mockito.mock(ApplicationArguments::class.java))

        assertEquals("wpsi", algorithmRegistry.defaultId())
        assertSame(WpsiPageSummaryAlgorithm, algorithmRegistry.resolve(null))
    }

    @Test
    @DisplayName("configured default pointing at an unknown id fails fast on resolve")
    fun configuredUnknownDefaultFailsFast() {
        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)
        Mockito.`when`(
            environment.getProperty(PageSummaryAlgorithmRegistry.CONFIG_KEY_DEFAULT_ALGORITHM)
        ).thenReturn("not-installed")

        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.environment).thenReturn(environment)
        Mockito.`when`(context.getBeansOfType(PluginMount::class.java)).thenReturn(emptyMap())
        Mockito.`when`(context.getBeansOfType(Browser4Plugin::class.java)).thenReturn(emptyMap())

        PluginManager(context).run(Mockito.mock(ApplicationArguments::class.java))

        assertEquals("not-installed", algorithmRegistry.defaultId())
        assertNull(algorithmRegistry.resolve(null))
        // Explicit ids still resolve.
        assertSame(WpsiPageSummaryAlgorithm, algorithmRegistry.resolve("wpsi"))
    }

    @Test
    @DisplayName("root code source (null file name) yields null instead of NPE")
    fun rootCodeSourceYieldsNull() {
        // "file:/" is the filesystem root: Path.getFileName() is null there.
        assertNull(manifestOfLocation(URI.create("file:/").toURL()))
    }

    @Test
    @DisplayName("directory code source (target/classes) yields null")
    fun directoryCodeSourceYieldsNull() {
        val classesDir = tempDir.resolve("target").resolve("classes")
        Files.createDirectories(classesDir)
        assertNull(manifestOfLocation(classesDir.toUri().toURL()))
    }

    @Test
    @DisplayName("nested fat-jar location yields null instead of NPE")
    fun nestedJarLocationYieldsNull() {
        // Spring Boot fat jars expose code sources like
        // file:/app/app.jar!/BOOT-INF/lib/plugin-1.0.0.jar!/ — not a readable file.
        val nested = URI.create("file:/app/app.jar!/BOOT-INF/lib/plugin-1.0.0.jar!/")
        assertNull(manifestOfLocation(nested.toURL()))
    }

    @Test
    @DisplayName("unreadable jar path yields null")
    fun unreadableJarYieldsNull() {
        val missing = tempDir.resolve("does-not-exist-1.0.0.jar")
        assertNull(manifestOfLocation(missing.toUri().toURL()))
    }

    @Test
    @DisplayName("plugin jar yields its manifest")
    fun pluginJarYieldsManifest() {
        val jarPath = createPluginJar("demo-plugin-1.0.0.jar", "demo-plugin")
        val manifest = manifestOfLocation(jarPath.toUri().toURL())
        assertNotNull(manifest)
        assertEquals("demo-plugin", manifest!!.name)
    }

    @Test
    @DisplayName("plain jar without plugin manifest yields null")
    fun plainJarYieldsNull() {
        val jarPath = tempDir.resolve("plain-lib-1.0.0.jar")
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { _ -> }
        assertNull(manifestOfLocation(jarPath.toUri().toURL()))
    }

    @Test
    @DisplayName("PageFormatContributorMount contributes formats into the registry on startup")
    fun wiresPageFormatContributorMount() {
        val branding = contributor("branding")
        val mount = object : PageFormatContributorMount {
            override fun getPageFormatContributors(): List<PageFormatContributor> = listOf(branding)
        }

        PluginManager(pluginContext(mount)).run(Mockito.mock(ApplicationArguments::class.java))

        assertEquals(1, formatRegistry.size())
        assertSame(branding, formatRegistry.get("branding"))
    }

    @Test
    @DisplayName("a refused contributor neither aborts startup nor loses its siblings")
    fun refusedContributorKeepsStartupAlive() {
        // `markdown` is a core format: register() refuses the id by throwing, so
        // the wiring has to catch per contributor rather than per mount.
        val mount = object : PageFormatContributorMount {
            override fun getPageFormatContributors(): List<PageFormatContributor> =
                listOf(contributor("markdown"), contributor("menu"))
        }

        PluginManager(pluginContext(mount)).run(Mockito.mock(ApplicationArguments::class.java))

        assertNull(formatRegistry.get("markdown"))
        assertEquals(1, formatRegistry.size())
        assertNotNull(formatRegistry.get("menu"))
    }

    // ---- Helpers ----

    /** A minimal contributor, distinguishable by identity in assertions. */
    private fun contributor(formatId: String): PageFormatContributor = object : PageFormatContributor {
        override val id = formatId
        override val displayName = formatId
        override val description = "contributed by PluginManagerTest"
        override val requires = emptySet<FormatInput>()

        override suspend fun contribute(ctx: FormatContext): Any? = formatId
    }

    /**
     * An application context exposing exactly [mounts] as plugin mounts and no
     * plugins — the same shape the wiring tests above build inline.
     */
    private fun pluginContext(vararg mounts: PluginMount): ApplicationContext {
        val environment = Mockito.mock(Environment::class.java)
        Mockito.`when`(environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false))
            .thenReturn(false)

        val context = Mockito.mock(ApplicationContext::class.java)
        Mockito.`when`(context.environment).thenReturn(environment)
        Mockito.`when`(context.getBeansOfType(PluginMount::class.java))
            .thenReturn(mounts.mapIndexed { i, mount -> "mount$i" to mount }.toMap())
        Mockito.`when`(context.getBeansOfType(Browser4Plugin::class.java))
            .thenReturn(emptyMap())
        return context
    }

    // ---- Helper ----

    private fun createPluginJar(fileName: String, name: String): Path {
        val manifestJson = """
            {
                "name": "$name",
                "version": "1.0.0",
                "defaultEnabled": true,
                "autoConfigurationClasses": ["java.lang.String"]
            }
        """.trimIndent()

        val jarPath = tempDir.resolve(fileName)
        val jdkManifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        }
        JarOutputStream(Files.newOutputStream(jarPath), jdkManifest).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/browser4-plugin.json"))
            jos.write(manifestJson.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarPath
    }
}
