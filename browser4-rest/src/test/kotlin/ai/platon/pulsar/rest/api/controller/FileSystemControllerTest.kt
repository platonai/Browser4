package ai.platon.pulsar.rest.api.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The workspace folder picker's backend (`GET api/system/fs/list`).
 *
 * It exists because a browser cannot name an absolute local path, so these tests pin the two
 * things the console depends on: a browsable listing of real directories, and a loud `400`
 * (with a reason) for anything a task workspace must not be.
 */
@Tag("Unit")
@Tag("Fast")
class FileSystemControllerTest {

    private val controller = FileSystemController()

    @Test
    @DisplayName("blank path returns browse roots instead of an error")
    fun blankPathReturnsRoots() {
        val response = controller.list(null)

        assertEquals(200, response.statusCode.value())
        val listing = checkNotNull(response.body)
        assertNull(listing.path, "roots level selects nothing")
        assertNull(listing.parent)
        assertTrue(listing.entries.isNotEmpty(), "at least one root entry is offered")
        assertTrue(
            listing.entries.all { Path.of(it.path).isAbsolute },
            "every root entry carries an absolute path",
        )
    }

    @Test
    @DisplayName("an existing directory lists its sub-directories, not its files")
    fun existingDirectoryListsSubDirectoriesOnly(@TempDir tempDir: Path) {
        Files.createDirectory(tempDir.resolve("alpha"))
        Files.createDirectory(tempDir.resolve("beta"))
        Files.createFile(tempDir.resolve("note.txt"))

        val response = controller.list(tempDir.toString())

        assertEquals(200, response.statusCode.value())
        val listing = checkNotNull(response.body)
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), listing.path)
        assertEquals(listOf("alpha", "beta"), listing.entries.map { it.name })
        assertTrue(
            listing.entries.all { Path.of(it.path).isAbsolute },
            "entries carry absolute paths the submit call can use as --workspace",
        )
        assertNull(listing.error)
    }

    @Test
    @DisplayName("a missing directory is rejected with a reason")
    fun missingDirectoryIsRejected(@TempDir tempDir: Path) {
        val response = controller.list(tempDir.resolve("does-not-exist").toString())

        assertEquals(400, response.statusCode.value())
        assertNotNull(checkNotNull(response.body).error)
    }

    @Test
    @DisplayName("a relative path is rejected instead of being resolved against the backend cwd")
    fun relativePathIsRejected() {
        val response = controller.list("some/relative/dir")

        assertEquals(400, response.statusCode.value())
        assertNotNull(checkNotNull(response.body).error)
    }

    @Test
    @DisplayName("a file is rejected: a workspace must be a directory")
    fun fileIsRejected(@TempDir tempDir: Path) {
        val file = Files.createFile(tempDir.resolve("plain.txt"))

        val response = controller.list(file.toString())

        assertEquals(400, response.statusCode.value())
        assertNotNull(checkNotNull(response.body).error)
    }

    @Test
    @DisplayName("a drive root reports no parent so the picker disables 上级")
    fun driveRootHasNoParent(@TempDir tempDir: Path) {
        val root = tempDir.root

        val response = controller.list(root.toString())

        assertEquals(200, response.statusCode.value())
        assertNull(checkNotNull(response.body).parent, "a drive root has no browsable parent")
    }
}
