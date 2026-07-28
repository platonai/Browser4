package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.plugin.PluginInfo
import ai.platon.browser4.boot.plugin.PluginService
import ai.platon.pulsar.common.getLogger
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * REST controller for managing runtime plugins.
 *
 * ## Endpoints
 *
 * | Method   | Path                       | Description              |
 * |----------|----------------------------|--------------------------|
 * | `GET`    | `/api/plugins`             | List all plugins         |
 * | `GET`    | `/api/plugins/{name}`      | Get one plugin           |
 * | `POST`   | `/api/plugins/install`     | Install from file upload |
 * | `DELETE` | `/api/plugins/{name}`      | Remove a plugin          |
 *
 * Installed plugins take effect after an application restart.
 */
@RestController
@CrossOrigin
@RequestMapping(
    "api/plugins",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class PluginController(
    private val pluginService: PluginService,
) {
    private val logger = getLogger(PluginController::class)

    /**
     * List all plugins in the [plugins] directory.
     */
    @GetMapping(value = ["", "/"])
    fun listPlugins(): ResponseEntity<List<PluginInfo>> {
        val plugins = pluginService.listPlugins()
        logger.info("Listed {} plugin(s)", plugins.size)
        return ResponseEntity.ok(plugins)
    }

    /**
     * Get details for a single plugin.
     *
     * @param name  the plugin manifest name or JAR file name
     */
    @GetMapping("/{name}")
    fun getPlugin(@PathVariable name: String): ResponseEntity<PluginInfo> {
        val plugin = pluginService.getPlugin(name)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(plugin)
    }

    /**
     * Install a plugin from a multipart file upload.
     *
     * The uploaded file must be a valid Browser4 plugin JAR (containing
     * `META-INF/browser4-plugin.json`).
     *
     * @param file    the plugin JAR file
     * @param replace if true, overwrite an existing plugin with the same name
     */
    @PostMapping("/install", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun installPlugin(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(value = "replace", defaultValue = "false") replace: Boolean,
    ): ResponseEntity<PluginInfo> {
        if (file.isEmpty) {
            return ResponseEntity.badRequest().build()
        }

        // Write the uploaded file to a temp location, then delegate to PluginService
        val tmpDir = Files.createTempDirectory("browser4-plugin-")
        val tmpFile = tmpDir.resolve(file.originalFilename ?: "plugin.jar")

        try {
            file.inputStream.use { input ->
                Files.copy(input, tmpFile)
            }

            val info = pluginService.installPlugin(tmpFile, replace)
            return ResponseEntity.ok(info)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid plugin upload: {}", e.message)
            return ResponseEntity.badRequest().body(null)
        } catch (e: IllegalStateException) {
            logger.warn("Plugin conflict: {}", e.message)
            return ResponseEntity.status(409).body(null) // Conflict
        } finally {
            // Clean up temp file
            try { Files.deleteIfExists(tmpFile) } catch (_: Exception) {}
            try { Files.deleteIfExists(tmpDir) } catch (_: Exception) {}
        }
    }

    /**
     * Remove a plugin by name.
     *
     * @param name  the plugin manifest name or JAR file name
     */
    @DeleteMapping("/{name}")
    fun removePlugin(@PathVariable name: String): ResponseEntity<PluginInfo> {
        return try {
            val info = pluginService.removePlugin(name)
            ResponseEntity.ok(info)
        } catch (e: IllegalArgumentException) {
            logger.warn("Plugin not found for removal: {}", name)
            ResponseEntity.notFound().build()
        } catch (e: IllegalStateException) {
            logger.warn("Plugin removal failed (file locked or I/O error): {}", e.message)
            ResponseEntity.status(409).body(null)
        }
    }
}
