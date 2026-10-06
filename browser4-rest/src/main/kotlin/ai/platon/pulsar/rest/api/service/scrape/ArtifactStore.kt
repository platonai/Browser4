package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.common.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * One kind of binary artifact: the extension that names it, where it is filed, and how
 * it is served back.
 *
 * The three facts live together on purpose. Split apart, "written under `web/pdf`" and
 * "served as `application/pdf`" drift, and the failure mode is an artifact that can be
 * produced but not retrieved (or retrieved as the wrong thing).
 *
 * @property extension lowercase, without the dot.
 * @property directory the directory this kind is filed in; always inside `AppPaths`.
 * @property contentType the media type to serve it with.
 */
data class ArtifactKind(
    val extension: String,
    val directory: Path,
    val contentType: String,
)

/**
 * Where the formats layer's binary artifacts live, and how one is addressed again.
 *
 * This is the **host's** side of the `ArtifactSpec` contract: a provider says "this
 * step's output is bytes of kind `pdf`" and never learns a path, while the host decides
 * placement — and, here, how a caller gets the bytes back.
 *
 * ## Why a file *name* is the address
 *
 * `POST /api/scrape` is synchronous and stateless: there is no task id to hang a
 * `/api/scrape/{id}/media/{name}` route on, and inventing one would mean returning an
 * identifier whose only meaning is "try again later". The name the writer generates is
 * already unique (a timestamp plus a random suffix), so it is the artifact's identity on
 * this host — and it is exactly the part of the returned path a caller can use without
 * the server having to trust a client-supplied path.
 *
 * ## The security rules, and why they are shaped this way
 *
 * A caller may only ever ask for a name, never a path. [resolve] then enforces:
 *
 * 1. **One path segment.** `/`, `\` and NUL are refused, so no input can name a file
 *    outside its directory however it is spelled. This is the rule that makes traversal
 *    impossible rather than merely unlikely.
 * 2. **A registered extension.** An unknown extension is not an artifact of this layer,
 *    so it is refused even if the file exists — the servable set and the writable set are
 *    the same set by construction ([kinds]).
 * 3. **A regular file directly in that kind's directory**, re-checked by containment
 *    after resolution. Belt and braces: rule 1 already makes this unreachable, and a
 *    check that cannot fire is worth keeping when the alternative is serving an
 *    arbitrary file.
 *
 * A refusal is [IllegalArgumentException] (a caller mistake, so the REST face answers
 * 400 by name) while a well-formed name that is simply not there is `null` (404).
 *
 * @param kinds the artifact kinds this deployment can write and serve.
 */
class ArtifactStore(
    private val kinds: List<ArtifactKind> = defaultKinds(),
) {
    private val byExtension: Map<String, ArtifactKind> = kinds.associateBy { it.extension }

    /**
     * File [bytes] under [nameHint] and return the path.
     *
     * @param nameHint a stem from the provider's `ArtifactSpec`, e.g. `screenshot`.
     * @param bytes the decoded payload; the caller owns base64 decoding so that a bad
     *   payload fails before anything is created.
     * @param extension the artifact kind, e.g. `png`; case and a leading dot are
     *   normalised away.
     * @throws IllegalArgumentException when [extension] does not name a registered kind.
     *   Refused rather than written into a guessed directory: an artifact nobody can
     *   serve is worse than a format that degrades with a reason.
     */
    fun write(nameHint: String, bytes: ByteArray, extension: String): Path {
        val kind = kindOf(extension)
        Files.createDirectories(kind.directory)
        val name = "$nameHint-${AppPaths.fromNow()}-${UUID.randomUUID().toString().take(8)}.${kind.extension}"
        val path = kind.directory.resolve(name)
        Files.write(path, bytes)
        return path
    }

    /**
     * The file a caller asked for by [name], or null when it is a valid artifact name
     * that is not there.
     *
     * @throws IllegalArgumentException when [name] cannot be an artifact name at all —
     *   see the class KDoc for the rules, which are refusals by design and not sanitised
     *   into something that happens to work.
     */
    fun resolve(name: String): Path? {
        requireArtifactName(name)
        val kind = byExtension[extensionOf(name)] ?: return null
        val path = kind.directory.resolve(name).normalize()
        if (!path.startsWith(kind.directory.normalize())) return null
        return path.takeIf { Files.isRegularFile(it) }
    }

    /** The registered kind for [name]'s extension, or null when it is not an artifact. */
    fun kindOfName(name: String): ArtifactKind? = byExtension[extensionOf(name)]

    /** The registered kind for [extension], or an [IllegalArgumentException] naming the known ones. */
    fun kindOf(extension: String): ArtifactKind {
        val leaf = extension.trim().lowercase().removePrefix(".")
        require(leaf.isNotEmpty()) { "an artifact needs an extension to be filed under" }
        return byExtension[leaf] ?: throw IllegalArgumentException(
            "unsupported artifact extension '$extension'; this deployment writes " +
                byExtension.keys.sorted().joinToString(", ")
        )
    }

    /**
     * Reject anything that is not a single, plain file name.
     *
     * Deliberately a `require` rather than a cleanup: the value arrives from a caller,
     * and a name that is rewritten into a working one hides the fact that the caller
     * sent a path.
     */
    private fun requireArtifactName(name: String) {
        require(name.isNotBlank()) { "an artifact name is required" }
        require(name.length <= MAX_NAME_LENGTH) { "artifact name is too long: ${name.length} characters" }
        require(name.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "invalid artifact name '$name': a name must not contain a path separator"
        }
        require(name.first().isLetterOrDigit()) {
            "invalid artifact name '$name': a name starts with a letter or a digit"
        }
    }

    /** The extension of [name], lowercased, or `""` when it has none. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    companion object {
        /** Long enough for the generated names, short enough to stay a name. */
        private const val MAX_NAME_LENGTH = 255

        /** The `png` kind: the directory this project already reserves for captures. */
        const val PNG = "png"

        /** The `pdf` kind. */
        const val PDF = "pdf"

        /**
         * The kinds this deployment writes, derived from `AppPaths`.
         *
         * `png` keeps [AppPaths.WEB_SCREENSHOT_DIR], the directory the project already
         * reserves for it; every other kind gets a sibling under the same `web/` parent,
         * named after its extension — so a PDF is filed in `web/pdf` rather than in a
         * folder called `screenshot`. Both roots are `AppPaths`, which is the constraint
         * the artifact policy sets.
         */
        fun defaultKinds(): List<ArtifactKind> = listOf(
            ArtifactKind(PNG, AppPaths.WEB_SCREENSHOT_DIR, "image/png"),
            ArtifactKind(PDF, AppPaths.WEB_CACHE_DIR.resolve(PDF), "application/pdf"),
        )
    }
}
