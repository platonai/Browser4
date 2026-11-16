package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Content fingerprint of a coding run's change set.
 *
 * `coding.verify` records the fingerprint of the tree it verified; the isolation gate
 * recomputes it against the tree it is about to merge. Without that binding, a run
 * could verify, keep editing, and still merge on the old verdict — the one thing the
 * verification gate was supposed to prevent ("verify passed" would describe code that
 * is no longer what lands).
 *
 * The fingerprint covers **exactly the changed paths** ([WorkspaceIsolation.changedFilesOf]),
 * so adding a file, reverting one or editing one after the verdict all change it.
 *
 * Cost and failure semantics: bounded ([MAX_FILES], [MAX_TOTAL_BYTES]) and returns
 * `null` when the change set is too large, a path escapes the root, or reading fails.
 * A `null` digest never blocks a merge on its own — the gate falls back to the verdict
 * timestamp — because "we could not fingerprint this" is not evidence of tampering.
 */
object WorkspaceDigest {

    private val logger = LoggerFactory.getLogger(WorkspaceDigest::class.java)

    /** More changed files than this are not fingerprinted (huge generated trees). */
    const val MAX_FILES = 400

    /** Total content bytes fingerprinted before giving up. */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /**
     * `sha256:<hex>` over the sorted `(relative path, content)` pairs of [changedFiles]
     * under [root], or null when it cannot be computed cheaply/safely.
     *
     * A path that no longer exists (a deletion) contributes a marker instead of
     * content: the deletion is part of the change set and must change the fingerprint.
     */
    fun of(root: Path?, changedFiles: Collection<String>): String? {
        val base = root?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() } ?: return null
        val files = changedFiles
            .map { it.trim().replace('\\', '/').removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        if (files.isEmpty() || files.size > MAX_FILES) return null

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            for (rel in files) {
                val path = base.resolve(rel).normalize()
                // A change set is repo-relative by construction; anything resolving
                // outside it (a `..`, a symlinked target) is not fingerprinted.
                if (!path.startsWith(base)) return null
                digest.update(rel.toByteArray())
                digest.update(0)
                if (!Files.isRegularFile(path)) {
                    digest.update("deleted".toByteArray())
                } else {
                    total += Files.size(path)
                    if (total > MAX_TOTAL_BYTES) return null
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                digest.update(0)
            }
            "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.debug("cannot fingerprint the change set under {}: {}", base, e.message)
            null
        }
    }
}
