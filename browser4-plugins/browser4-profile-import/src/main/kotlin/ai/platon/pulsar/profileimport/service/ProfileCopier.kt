package ai.platon.pulsar.profileimport.service

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Copies a whole Chrome/Edge profile directory into a destination, skipping
 * large non-auth cache directories and per-process lock files. The copy is a
 * best-effort snapshot: individual file failures are logged and skipped.
 *
 * The source browser must be fully closed before copying — on Windows the
 * profile is locked while the browser runs, and `SingletonLock` presence is a
 * reliable signal everywhere.
 */
open class ProfileCopier {

    companion object {
        private val logger = LoggerFactory.getLogger(ProfileCopier::class.java)

        /** Large directories that are not needed to reuse profile data. */
        val EXCLUDE_DIRS: Set<String> = setOf(
            "Cache",
            "Code Cache",
            "GPUCache",
            "Service Worker",
            "blob_storage",
            "File System",
            "GCM Store",
            "optimization_guide",
            "ShaderCache",
            "component_crx_cache",
        )

        /** Per-process lock files that must never be copied. */
        val LOCK_FILES: Set<String> = setOf(
            "SingletonLock",
            "SingletonSocket",
            "SingletonCookie",
        )
    }

    /**
     * Copies [source] (a profile directory, e.g. `User Data/Default`) into
     * [dest], along with the user-data-level `Local State` file placed next to
     * it. Returns the number of files copied.
     *
     * @throws IllegalStateException when the source browser is running
     *         (`SingletonLock` present in the profile).
     */
    fun copyProfile(userDataDir: Path, profile: SourceProfile, dest: Path): Int {
        val profileDir = profile.profileDir
        require(Files.isDirectory(profileDir)) {
            "Profile directory not found: $profileDir"
        }
        if (Files.exists(profileDir.resolve("SingletonLock"))) {
            throw IllegalStateException(
                "The source browser is running (${profile.ident}). Close it completely " +
                    "before importing, then retry."
            )
        }

        Files.createDirectories(dest)

        // Copy the user-data-level Local State so the copied profile keeps its
        // encryption key references (DPAPI / Keychain / app-bound are resolved
        // by the OS at launch time; Local State carries the os_crypt payload).
        val localState = userDataDir.resolve("Local State")
        if (Files.isRegularFile(localState)) {
            Files.copy(localState, dest.resolve("Local State"), StandardCopyOption.REPLACE_EXISTING)
        }

        return copyTree(profileDir, dest.resolve(profile.directory))
    }

    /**
     * Recursively copies [src] to [dst], skipping [EXCLUDE_DIRS] directories
     * and [LOCK_FILES] files. Individual failures are logged, not fatal.
     */
    private fun copyTree(src: Path, dst: Path): Int {
        var count = 0
        Files.createDirectories(dst)
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName.toString()
                if (dir != src && name in EXCLUDE_DIRS) {
                    logger.debug("Skipping excluded directory {}", dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                Files.createDirectories(dst.resolve(src.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (name in LOCK_FILES) {
                    logger.debug("Skipping lock file {}", file)
                    return FileVisitResult.CONTINUE
                }
                try {
                    Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
                    count++
                } catch (e: IOException) {
                    logger.warn("Failed to copy {}: {}", file, e.message)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                logger.warn("Failed to visit {}: {}", file, exc.message)
                return FileVisitResult.CONTINUE
            }
        })
        return count
    }
}
