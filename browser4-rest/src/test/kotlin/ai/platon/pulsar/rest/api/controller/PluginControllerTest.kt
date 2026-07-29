package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.plugin.PluginInfo
import ai.platon.browser4.boot.plugin.PluginService
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Path

class PluginControllerTest {

    private val sampleManifest = PluginManifest(
        name = "test-plugin",
        version = "1.0.0",
        description = "A test plugin",
        dependsOn = listOf("browser4-skeleton"),
        autoConfigurationClasses = listOf("com.example.TestAutoConfiguration"),
    )

    private val samplePluginInfo = PluginInfo(
        fileName = "test-plugin-1.0.0.jar",
        fileSize = 42_000,
        path = "/tmp/plugins/test-plugin-1.0.0.jar",
        manifest = sampleManifest,
        loaded = true,
    )

    // ---- listPlugins() ----

    @Test
    fun `listPlugins returns plugin list from service`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.listPlugins()).thenReturn(listOf(samplePluginInfo))
        val controller = PluginController(service)

        val response = controller.listPlugins()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.size)
        assertEquals("test-plugin-1.0.0.jar", response.body?.get(0)?.fileName)
        Mockito.verify(service).listPlugins()
    }

    @Test
    fun `listPlugins returns empty list when no plugins installed`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.listPlugins()).thenReturn(emptyList())
        val controller = PluginController(service)

        val response = controller.listPlugins()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isEmpty())
    }

    // ---- getPlugin() ----

    @Test
    fun `getPlugin returns 200 when plugin found`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.getPlugin("test-plugin")).thenReturn(samplePluginInfo)
        val controller = PluginController(service)

        val response = controller.getPlugin("test-plugin")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("test-plugin", response.body?.manifest?.name)
        Mockito.verify(service).getPlugin("test-plugin")
    }

    @Test
    fun `getPlugin returns 404 when plugin not found`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.getPlugin("nonexistent")).thenReturn(null)
        val controller = PluginController(service)

        val response = controller.getPlugin("nonexistent")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNull(response.body)
    }

    // ---- installPlugin() ----

    @Test
    fun `installPlugin returns 200 on successful install`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.installPlugin(any<Path>(), any())).thenReturn(samplePluginInfo)
        val controller = PluginController(service)

        val file = MockMultipartFile(
            "file", "test-plugin-1.0.0.jar",
            "application/java-archive", "fake-jar-content".toByteArray()
        )

        val response = controller.installPlugin(file, replace = false)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("test-plugin-1.0.0.jar", response.body?.fileName)
        Mockito.verify(service).installPlugin(any<Path>(), any())
    }

    @Test
    fun `installPlugin returns 400 when file is empty`() {
        val service = Mockito.mock(PluginService::class.java)
        val controller = PluginController(service)

        val emptyFile = MockMultipartFile(
            "file", "empty.jar",
            "application/java-archive", ByteArray(0)
        )

        val response = controller.installPlugin(emptyFile, replace = false)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `installPlugin returns 400 for invalid plugin JAR`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.installPlugin(any<Path>(), any()))
            .thenThrow(IllegalArgumentException("Not a valid Browser4 plugin"))
        val controller = PluginController(service)

        val file = MockMultipartFile(
            "file", "not-a-plugin.jar",
            "application/java-archive", "not-real-content".toByteArray()
        )

        val response = controller.installPlugin(file, replace = false)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `installPlugin returns 409 on duplicate without replace`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.installPlugin(any<Path>(), any()))
            .thenThrow(IllegalStateException("Plugin 'test-plugin' is already installed"))
        val controller = PluginController(service)

        val file = MockMultipartFile(
            "file", "test-plugin-1.0.0.jar",
            "application/java-archive", "fake-content".toByteArray()
        )

        val response = controller.installPlugin(file, replace = false)

        assertEquals(409, response.statusCode.value())
    }

    // ---- removePlugin() ----

    @Test
    fun `removePlugin returns 200 on successful removal`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.removePlugin("test-plugin")).thenReturn(samplePluginInfo)
        val controller = PluginController(service)

        val response = controller.removePlugin("test-plugin")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("test-plugin-1.0.0.jar", response.body?.fileName)
        Mockito.verify(service).removePlugin("test-plugin")
    }

    @Test
    fun `removePlugin returns 404 when plugin not found`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.removePlugin("nonexistent"))
            .thenThrow(IllegalArgumentException("No plugin found matching 'nonexistent'"))
        val controller = PluginController(service)

        val response = controller.removePlugin("nonexistent")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `removePlugin returns 409 when file is locked or I-O error occurs`() {
        val service = Mockito.mock(PluginService::class.java)
        Mockito.`when`(service.removePlugin("browser4-media"))
            .thenThrow(IllegalStateException(
                "Failed to delete plugin 'browser4-media-4.12.1-SNAPSHOT.jar': " +
                    "The process cannot access the file because it is being used by another process. " +
                    "Stop the application, delete the file manually, then restart."
            ))
        val controller = PluginController(service)

        val response = controller.removePlugin("browser4-media")

        assertEquals(409, response.statusCode.value())
        assertNull(response.body)
    }
}
