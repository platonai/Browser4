package ai.platon.pulsar.rest.api.service.scrape

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("ArtifactStore")
class ArtifactStoreTest {

    /**
     * A store over temp directories rather than the real `AppPaths` ones.
     *
     * The point of the injected kind table: the rules under test are about *names* and
     * *containment*, and they can be exercised — including the refusals — without
     * scattering files in the process temp tree.
     */
    private fun store(root: Path) = ArtifactStore(
        listOf(
            ArtifactKind("png", root.resolve("screenshot"), "image/png"),
            ArtifactKind("pdf", root.resolve("pdf"), "application/pdf"),
        ),
    )

    private val payload = "%PDF-1.4 fake".toByteArray()

    // ---- writing ------------------------------------------------------------

    @Test
    @DisplayName("bytes are written under the kind's directory, with the hint and extension")
    fun writeFilesByKind(@TempDir root: Path) {
        val path = store(root).write("pdf", payload, "pdf")

        assertTrue(Files.exists(path), "expected a real file at $path")
        assertEquals(root.resolve("pdf"), path.parent)
        assertTrue(path.fileName.toString().startsWith("pdf-"), path.toString())
        assertTrue(path.fileName.toString().endsWith(".pdf"), path.toString())
        assertTrue(path.fileName.toString() != "pdf.pdf", "the name must be unique, was ${path.fileName}")
    }

    @Test
    @DisplayName("two writes never collide, even for the same hint in the same second")
    fun writeNamesAreUnique(@TempDir root: Path) {
        val store = store(root)

        val first = store.write("pdf", payload, "pdf")
        val second = store.write("pdf", payload, "pdf")

        // The suffix is what makes a name usable as an artifact's identity: two concurrent
        // requests can print the same page in the same second.
        assertTrue(first != second, "expected distinct paths, both were $first")
    }

    @Test
    @DisplayName("an extension and a leading dot are normalised before use")
    fun writeNormalisesTheExtension(@TempDir root: Path) {
        val path = store(root).write("pdf", payload, ".PDF")

        assertTrue(path.parent == root.resolve("pdf"), "expected web/pdf, got ${path.parent}")
        assertTrue(path.fileName.toString().endsWith(".pdf"), path.toString())
    }

    @Test
    @DisplayName("an extension this deployment cannot serve is refused, naming the known ones")
    fun writeRefusesAnUnregisteredExtension(@TempDir root: Path) {
        // Refused rather than filed in a guessed directory: an artifact nobody can serve
        // is worse than a format that degrades with a reason.
        for (extension in listOf("", "   ", "mp4", "../pdf", "p/d")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                store(root).write("movie", payload, extension)
            }
            if (extension.isNotBlank()) {
                assertTrue(
                    error.message!!.contains("pdf") && error.message!!.contains("png"),
                    "expected the known kinds in: ${error.message}",
                )
            }
        }
    }

    // ---- resolving ----------------------------------------------------------

    @Test
    @DisplayName("a written artifact resolves back to its own path")
    fun resolveFindsAWrittenArtifact(@TempDir root: Path) {
        val store = store(root)
        val written = store.write("pdf", payload, "pdf")

        val resolved = store.resolve(written.fileName.toString())

        assertEquals(written, resolved)
        assertEquals("application/pdf", store.kindOfName(written.fileName.toString())?.contentType)
    }

    @Test
    @DisplayName("a name carrying a path is refused, not cleaned up")
    fun resolveRefusesAnythingThatIsNotAName(@TempDir root: Path) {
        val store = store(root)

        // The rule that makes traversal impossible rather than unlikely: with no
        // separator reachable, no spelling can name a file outside its directory. It is a
        // refusal rather than a sanitise because a caller who sent a path should be told.
        val notNames = listOf(
            "", "   ", "../secret.pdf", "..\\secret.pdf", "/etc/passwd", "a/b.pdf", "a\\b.pdf",
            ".hidden.pdf", "-leading-dash.pdf",
        )
        for (name in notNames) {
            assertThrows(IllegalArgumentException::class.java, { store.resolve(name) }, "name='$name'")
        }
    }

    @Test
    @DisplayName("a well-formed name that is not an artifact is null, not an error")
    fun resolveReturnsNullForNonArtifacts(@TempDir root: Path) {
        val store = store(root)

        // 404 territory rather than 400: the caller spelled a name correctly, there is
        // just no such artifact. An unknown extension is one of those cases — the file
        // could exist on disk and still not be ours to serve.
        assertNull(store.resolve("notes.txt"))
        assertNull(store.resolve("nosuchextension"))
        assertNull(store.resolve("pdf-20261007-010203-456-abcdef01.pdf"))

        Files.createDirectories(root.resolve("pdf"))
        Files.write(root.resolve("notes.txt"), payload)
        assertNull(store.resolve("notes.txt"), "an unregistered extension is not servable")
    }

    @Test
    @DisplayName("a directory named like an artifact is not served as one")
    fun resolveIgnoresDirectories(@TempDir root: Path) {
        val store = store(root)
        val directory = root.resolve("pdf").resolve("pdf-20261007-010203-456-abcdef01.pdf")
        Files.createDirectories(directory)

        assertNull(store.resolve(directory.fileName.toString()))
    }

    @Test
    @DisplayName("kindOfName answers only for registered extensions")
    fun kindOfNameIsCaseInsensitive(@TempDir root: Path) {
        val store = store(root)

        assertEquals("image/png", store.kindOfName("shot.PNG")?.contentType)
        assertNull(store.kindOfName("shot.gif"))
        assertNull(store.kindOfName("noextension"))
    }

    @Test
    @DisplayName("the default kinds are the AppPaths directories this project reserves")
    fun defaultKindsComeFromAppPaths() {
        val kinds = ArtifactStore.defaultKinds().associateBy { it.extension }

        // The artifact policy's constraint: files live where AppPaths says. `png` keeps
        // the capture directory the project already had; `pdf` gets its own sibling, so
        // the directory never contradicts what is inside it.
        val png = kinds.getValue("png")
        val pdf = kinds.getValue("pdf")
        assertTrue(png.directory.endsWith("screenshot"), png.directory.toString())
        assertTrue(pdf.directory.endsWith("pdf"), pdf.directory.toString())
        assertEquals(png.directory.parent, pdf.directory.parent, "both must share the web/ parent")
        assertFalse(png.directory == pdf.directory)
    }
}
