package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ReadablePaths]: the Browser4-owned folders a coding task may read outside its workspace.
 *
 * The behaviour these tests pin is the fix for a real refusal: a run asked to inspect the previous
 * task's log got `PERMISSION_DENIED | code=PATH_DENIED` for `~/.browser4/logs/...`, which is the
 * agent's own bookkeeping. The data root and the checkout are readable by default, an operator can
 * add more with `browser4.agent.readPaths`, and the OS credential stores stay refused whatever is
 * configured.
 *
 * Every test saves and restores the JVM properties it touches: [ReadablePaths] resolves them on
 * every call (memoized against their values), so a leaked property would change the scope of every
 * later test in the same fork.
 */
class ReadablePathsTest {

    @TempDir
    lateinit var tempDir: Path

    private val savedProperties = mutableMapOf<String, String?>()

    @BeforeEach
    fun saveProperties() {
        for (name in listOf(
            ReadablePaths.PROP_READ_PATHS,
            ReadablePaths.PROP_APP_DATA_DIR,
            "user.home",
        )) {
            savedProperties[name] = System.getProperty(name)
        }
    }

    @AfterEach
    fun restoreProperties() {
        savedProperties.forEach { (name, value) ->
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
        savedProperties.clear()
    }

    @Test
    @DisplayName("configured roots split on the platform separator and on ';'")
    fun testConfiguredRootsSplitOnSeparators() {
        val first = Files.createDirectories(tempDir.resolve("first"))
        val second = Files.createDirectories(tempDir.resolve("second"))
        System.setProperty(
            ReadablePaths.PROP_READ_PATHS,
            "$first${File.pathSeparator}$second;",
        )

        assertEquals(
            listOf(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
            ReadablePaths.configuredRoots(),
        )
    }

    @Test
    @DisplayName("a ~ entry is expanded against user.home")
    fun testTildeEntriesAreExpanded() {
        val home = Files.createDirectories(tempDir.resolve("home"))
        System.setProperty("user.home", home.toString())
        System.setProperty(ReadablePaths.PROP_READ_PATHS, "~/corpus")

        assertEquals(listOf(home.resolve("corpus").normalize()), ReadablePaths.configuredRoots())
    }

    @Test
    @DisplayName("a blank or unparsable entry is ignored instead of failing the task")
    fun testBadEntriesAreIgnored() {
        val valid = Files.createDirectories(tempDir.resolve("valid"))
        System.setProperty(ReadablePaths.PROP_READ_PATHS, " ;; $valid ; ;")

        assertEquals(listOf(valid.toAbsolutePath().normalize()), ReadablePaths.configuredRoots())
    }

    @Test
    @DisplayName("the Browser4 data root is a built-in readable root, so its logs are readable")
    fun testDataRootIsBuiltIn() {
        val data = Files.createDirectories(tempDir.resolve("appdata"))
        System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, data.toString())

        assertTrue(ReadablePaths.roots().contains(data.toAbsolutePath().normalize()))
        assertTrue(ReadablePaths.isReadable(data.resolve("logs/browser4.log")))
    }

    @Test
    @DisplayName("without app.data.dir the data root is ~/.browser4")
    fun testDataRootFallsBackToHome() {
        val home = Files.createDirectories(tempDir.resolve("home"))
        System.setProperty("user.home", home.toString())
        System.clearProperty(ReadablePaths.PROP_APP_DATA_DIR)

        assertEquals(home.resolve(ReadablePaths.DEFAULT_DATA_DIR_NAME), ReadablePaths.appDataRoot())
    }

    @Test
    @DisplayName("a configured folder is readable without naming the data root")
    fun testConfiguredFolderIsReadable() {
        val corpus = Files.createDirectories(tempDir.resolve("corpus"))
        val data = Files.createDirectories(tempDir.resolve("appdata2"))
        System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, data.toString())
        System.setProperty(ReadablePaths.PROP_READ_PATHS, corpus.toString())

        assertTrue(ReadablePaths.isReadable(corpus.resolve("notes/a.txt")))
        assertFalse(ReadablePaths.isReadable(tempDir.resolve("other/a.txt")))
    }

    @Test
    @DisplayName("the OS credential stores stay refused even when a readable root is their ancestor")
    fun testCredentialStoresStayRefused() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        // Lexical only: the key does not have to exist for the rail to apply.
        val key = home.resolve(".ssh/id_rsa")
        val sensitive = SensitivePaths.roots(CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS)

        assertTrue(ReadablePaths.isReadable(key, listOf(home)), "the readable root is the whole home")
        assertTrue(ReadablePaths.isAlwaysRefused(key))
        assertTrue(
            SensitivePaths.isRefused(key, sensitive, listOf(home)),
            "a readable root must never relax ~/.ssh",
        )
    }

    @Test
    @DisplayName("a readable root relaxes the sensitive entry that covers it — the real data-root layout")
    fun testDataRootRelaxesItsSensitiveEntry() {
        val data = Files.createDirectories(tempDir.resolve("appdata3"))
        val config = Files.createDirectories(data.resolve("config"))
        val providers = config.resolve("providers.json")
        Files.writeString(providers, "{}")
        // In production the data root IS the `~/.browser4` sensitive entry. Here it is a temp dir, so
        // the same shape is built explicitly: the root is sensitive and readable at the same time.
        val sensitive = SensitivePaths.roots(setOf(data.toString()))

        assertTrue(
            CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS.contains("~/.browser4"),
            "the production data root must be on the default sensitive list for this relaxation to matter",
        )
        assertTrue(SensitivePaths.isSensitive(config, sensitive), "the data root is on the sensitive list")
        assertTrue(SensitivePaths.isRefused(config.resolve("logs/x.log"), sensitive, emptyList()))

        // With the readable root in effect, the data root is readable — this is the whole point.
        assertFalse(SensitivePaths.isRefused(config.resolve("logs/x.log"), sensitive, listOf(data)))
        assertFalse(SensitivePaths.isRefused(providers, sensitive, listOf(data)))
    }
}
