package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.common.getLogger
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * REST controller for managing skills.
 *
 * ## Endpoints
 *
 * | Method   | Path                       | Description              |
 * |----------|----------------------------|--------------------------|
 * | `GET`    | `/api/skills`              | List all skills          |
 * | `GET`    | `/api/skills/{id}`         | Get one skill detail     |
 * | `POST`   | `/api/skills/install`      | Install from path or zip |
 * | `DELETE` | `/api/skills/{id}`         | Uninstall a skill        |
 * | `POST`   | `/api/skills/{id}/reload`  | Reload a skill           |
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/skills",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class SkillController(
    private val skillService: SkillService,
) {
    private val logger = getLogger(SkillController::class)

    /**
     * List all registered skills as lightweight summaries.
     */
    @GetMapping(value = ["", "/"])
    fun listSkills(): ResponseEntity<List<SkillService.SkillDetail>> {
        val summaries = skillService.listSkills()
        logger.info("Listed {} skill(s)", summaries.size)

        // Convert summaries to detail-like format for the response
        val details = summaries.map { summary ->
            SkillService.SkillDetail(
                id = summary.id,
                name = summary.name,
                version = summary.version,
                description = summary.description,
                author = "",
                tags = summary.tags.toList(),
                dependencies = emptyList(),
                skillMd = "",
                scriptsPath = null,
                referencesPath = null,
                assetsPath = null,
                origin = null,
            )
        }
        return ResponseEntity.ok(details)
    }

    /**
     * Get detailed information about a skill.
     *
     * @param id  the skill identifier
     */
    @GetMapping("/{id}")
    fun getSkill(@PathVariable id: String): ResponseEntity<SkillService.SkillDetail> {
        val detail = skillService.getSkill(id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(detail)
    }

    /**
     * Install a skill from a server-side directory path or a multipart zip upload.
     *
     * When [sourcePath] is provided, the skill is installed directly from that directory.
     * Otherwise, a multipart zip file upload is expected. The zip must contain a
     * skill directory with a `SKILL.md` at its root.
     *
     * @param sourcePath  optional path to a skill directory on the server filesystem
     * @param file        optional zip file containing the skill directory
     * @param overwrite   if true, overwrite an existing skill with the same ID
     */
    @PostMapping("/install", consumes = [MediaType.ALL_VALUE])
    suspend fun installSkill(
        @RequestParam(value = "path", required = false) sourcePath: String?,
        @RequestParam(value = "file", required = false) file: MultipartFile?,
        @RequestParam(value = "overwrite", defaultValue = "false") overwrite: Boolean,
    ): ResponseEntity<Map<String, Any?>> {
        if (sourcePath != null) {
            // Install from server-side path
            val sourceDir = Path.of(sourcePath)
            if (!Files.isDirectory(sourceDir)) {
                return ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to "Source path is not a directory: $sourcePath")
                )
            }

            return try {
                val result = skillService.installSkill(sourceDir, overwrite)
                ResponseEntity.ok(
                    mapOf(
                        "success" to result.success,
                        "skillId" to result.skillId,
                        "message" to result.message,
                        "deployedPath" to result.deployedPath,
                    )
                )
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid skill install request: {}", e.message)
                ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to (e.message ?: "Invalid request"))
                )
            } catch (e: IllegalStateException) {
                logger.warn("Skill conflict: {}", e.message)
                ResponseEntity.status(409).body(
                    mapOf("success" to false, "message" to (e.message ?: "Conflict"))
                )
            }
        }

        if (file != null && !file.isEmpty) {
            // Install from uploaded zip
            val tmpDir = Files.createTempDirectory("browser4-skill-")
            try {
                val tmpZip = tmpDir.resolve(file.originalFilename ?: "skill.zip")
                file.inputStream.use { input ->
                    Files.copy(input, tmpZip)
                }

                // Extract zip to temp directory
                val extractDir = tmpDir.resolve("extracted")
                Files.createDirectories(extractDir)
                extractZip(tmpZip, extractDir)

                // Find the skill directory (look for SKILL.md)
                val skillDir = findSkillDir(extractDir)
                    ?: return ResponseEntity.badRequest().body(
                        mapOf("success" to false, "message" to "No SKILL.md found in uploaded zip")
                    )

                val result = skillService.installSkill(skillDir, overwrite)
                return ResponseEntity.ok(
                    mapOf(
                        "success" to result.success,
                        "skillId" to result.skillId,
                        "message" to result.message,
                        "deployedPath" to result.deployedPath,
                    )
                )
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid skill upload: {}", e.message)
                ResponseEntity.badRequest().body(
                    mapOf("success" to false, "message" to (e.message ?: "Invalid request"))
                )
            } catch (e: IllegalStateException) {
                logger.warn("Skill conflict: {}", e.message)
                ResponseEntity.status(409).body(
                    mapOf("success" to false, "message" to (e.message ?: "Conflict"))
                )
            } finally {
                try { tmpDir.toFile().deleteRecursively() } catch (_: Exception) {}
            }
        }

        return ResponseEntity.badRequest().body(
            mapOf("success" to false, "message" to "Either 'path' or 'file' parameter is required")
        )
    }

    /**
     * Uninstall a skill by ID.
     *
     * @param id  the skill identifier
     */
    @DeleteMapping("/{id}")
    suspend fun uninstallSkill(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        return try {
            val result = skillService.uninstallSkill(id)
            if (result.success) {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "skillId" to result.skillId,
                        "message" to result.message,
                    )
                )
            } else {
                ResponseEntity.badRequest().body(
                    mapOf("success" to false, "skillId" to result.skillId, "message" to result.message)
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to uninstall skill '{}': {}", id, e.message)
            ResponseEntity.internalServerError().body(
                mapOf("success" to false, "message" to (e.message ?: "Unknown error"))
            )
        }
    }

    /**
     * Reload a skill from its source directory.
     *
     * @param id  the skill identifier
     */
    @PostMapping("/{id}/reload")
    suspend fun reloadSkill(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        return try {
            val reloaded = skillService.reloadSkill(id)
            if (reloaded) {
                ResponseEntity.ok(
                    mapOf("success" to true, "skillId" to id, "message" to "Skill '$id' reloaded successfully")
                )
            } else {
                ResponseEntity.status(500).body(
                    mapOf("success" to false, "skillId" to id, "message" to "Failed to reload skill '$id'")
                )
            }
        } catch (e: IllegalArgumentException) {
            logger.warn("Skill not found for reload: {}", id)
            ResponseEntity.notFound().build()
        } catch (e: IllegalStateException) {
            logger.warn("Cannot reload skill '{}': {}", id, e.message)
            ResponseEntity.badRequest().body(
                mapOf("success" to false, "skillId" to id, "message" to (e.message ?: "Cannot reload"))
            )
        }
    }

    // ---- Helpers ----

    private fun extractZip(zipPath: Path, targetDir: Path) {
        val buffer = ByteArray(4096)
        java.util.zip.ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = targetDir.resolve(entry.name).normalize()
                if (!entryPath.startsWith(targetDir)) {
                    throw IllegalArgumentException("Zip entry is outside of target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.newOutputStream(entryPath).use { output ->
                        var len = zis.read(buffer)
                        while (len > 0) {
                            output.write(buffer, 0, len)
                            len = zis.read(buffer)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun findSkillDir(root: Path): Path? {
        // If root itself has SKILL.md, return it
        if (Files.exists(root.resolve("SKILL.md"))) {
            return root
        }
        // Otherwise, search one level deep
        try {
            Files.list(root).use { dirs ->
                for (dir in dirs.filter { Files.isDirectory(it) }) {
                    if (Files.exists(dir.resolve("SKILL.md"))) {
                        return dir
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }
        return null
    }
}
