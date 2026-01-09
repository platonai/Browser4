package ai.platon.pulsar.common

import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.SystemUtils
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestRuntimes {
    @Test
    fun testEnv() {
        printlnPro(System.getenv("USER"))
    }

    @Test
    fun testCheckIfProcessRunning() {
        val running = Runtimes.checkIfProcessRunning("java")
        assertTrue { running }
    }

    @Test
    fun testLocateBinary() {
        if (SystemUtils.IS_OS_LINUX) {
            val locations = Runtimes.locateBinary("ls")
            assertTrue { locations.isNotEmpty() }
        }
    }

    @Test
    fun testDeleteBrokenSymbolicLinksUsingBash() {
        if (SystemUtils.IS_OS_WINDOWS) {
            System.err.println("Files.createSymbolicLink failed on Windows")
            return
        }

        val tmp = AppPaths.getTmpDirectory("test")
        val file = tmp.resolve(RandomStringUtils.secure().nextAlphabetic(5))
        Files.createDirectories(file.parent)
        Files.writeString(file, "to be deleted")
        val symbolicPath = tmp.resolve(RandomStringUtils.secure().nextAlphabetic(5))
        Files.createSymbolicLink(symbolicPath, file)

        assertTrue { Files.exists(file) }
        assertTrue { Files.exists(symbolicPath) }

        Files.delete(file)
        assertFalse { Files.exists(file) }
        assertFalse { Files.exists(symbolicPath) }
        assertTrue { Files.isSymbolicLink(symbolicPath) }

        Runtimes.deleteBrokenSymbolicLinks(tmp)
        if (SystemUtils.IS_OS_WINDOWS) {
            // TODO: what happens on windows
        } else {
            assertFalse { Files.isSymbolicLink(symbolicPath) }
        }
    }

    @Test
    fun testDeleteBrokenSymbolicLinksUsingJava() {
        val tmpDir = AppPaths.getTmpDirectory("test")
        val file = tmpDir.resolve(RandomStringUtils.secure().nextAlphanumeric(5))
        Files.createDirectories(file.parent)
        Files.writeString(file, "to be deleted")
        val symbolicPath = tmpDir.resolve(RandomStringUtils.secure().nextAlphabetic(5))
        AppFiles.createSymbolicLink(symbolicPath, file)

        assertTrue { Files.exists(file) }
        assertTrue { Files.exists(symbolicPath) }

        Files.delete(file)
        assertFalse { Files.exists(file) }
        if (AppFiles.supportSymbolicLink(symbolicPath)) {
            assertFalse { Files.exists(symbolicPath) }
            assertTrue { Files.isSymbolicLink(symbolicPath) }
        }

        Files.list(tmpDir).filter { Files.isSymbolicLink(it) && !Files.exists(it) }.forEach { Files.delete(it) }

        assertFalse { Files.isSymbolicLink(symbolicPath) }
    }

    @Test
    fun testUnallocatedDiskSpaces() {
        FileSystems.getDefault().fileStores.forEach {
            try {
                printlnPro(
                    String.format(
                        "%-30s%-10s%-20s%s", it.name(), it.type(),
                        Strings.compactFormat(it.unallocatedSpace), Strings.compactFormat(it.totalSpace)
                    )
                )
            } catch (e: Exception) {
                printlnPro(e.message)
            }
        }

        val spaces = Runtimes.unallocatedDiskSpaces()
        assertTrue { spaces.isNotEmpty() }
    }
}
