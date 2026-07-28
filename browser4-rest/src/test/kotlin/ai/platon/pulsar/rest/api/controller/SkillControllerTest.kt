package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.http.HttpStatus
import java.nio.file.Path

class SkillControllerTest {

    private val sampleDetail = SkillService.SkillDetail(
        id = "web-scraping",
        name = "Web Scraping",
        version = "1.0.0",
        description = "Extract data from web pages",
        author = "platon",
        tags = listOf("scraping", "extraction"),
        dependencies = emptyList(),
        skillMd = "# Web Scraping\n\nExtract data from web pages.",
        scriptsPath = null,
        referencesPath = null,
        assetsPath = null,
        origin = null,
    )

    private val sampleSummary = SkillRegistry.SkillSummary(
        id = "web-scraping",
        name = "Web Scraping",
        description = "Extract data from web pages",
        version = "1.0.0",
        tags = setOf("scraping", "extraction"),
    )

    private val sampleInstallResult = SkillInstaller.InstallResult(
        success = true,
        skillId = "web-scraping",
        message = "Skill 'web-scraping' installed successfully",
        deployedPath = "/tmp/skills/web-scraping",
    )

    // ---- listSkills() ----

    @Test
    @DisplayName("listSkills returns skill list from service")
    fun testListSkillsReturnsSkillListFromService() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.listSkills()).thenReturn(listOf(sampleSummary))
        val controller = SkillController(service)

        val response = controller.listSkills()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.size)
        assertEquals("web-scraping", response.body?.get(0)?.id)
        Mockito.verify(service).listSkills()
    }

    @Test
    @DisplayName("listSkills returns empty list when no skills")
    fun testListSkillsReturnsEmptyListWhenNoSkills() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.listSkills()).thenReturn(emptyList())
        val controller = SkillController(service)

        val response = controller.listSkills()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isEmpty())
    }

    // ---- getSkill() ----

    @Test
    @DisplayName("getSkill returns 200 when skill found")
    fun testGetSkillReturns200WhenFound() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.getSkill("web-scraping")).thenReturn(sampleDetail)
        val controller = SkillController(service)

        val response = controller.getSkill("web-scraping")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("web-scraping", response.body?.id)
        assertEquals("Web Scraping", response.body?.name)
        Mockito.verify(service).getSkill("web-scraping")
    }

    @Test
    @DisplayName("getSkill returns 404 when skill not found")
    fun testGetSkillReturns404WhenNotFound() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.getSkill("nonexistent")).thenReturn(null)
        val controller = SkillController(service)

        val response = controller.getSkill("nonexistent")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        Mockito.verify(service).getSkill("nonexistent")
    }

    // ---- installSkill() ----

    @Test
    @DisplayName("installSkill via path returns 200 on success")
    fun testInstallSkillViaPathReturns200OnSuccess(@TempDir tempDir: Path) = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.installSkill(any(), any())).thenReturn(sampleInstallResult)
        val controller = SkillController(service)

        val response = controller.installSkill(
            sourcePath = tempDir.toString(),
            file = null,
            overwrite = false,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals(true, body["success"])
        assertEquals("web-scraping", body["skillId"])
    }

    @Test
    @DisplayName("installSkill returns 400 when no path or file provided")
    fun testInstallSkillReturns400WhenNoPathOrFile() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        val controller = SkillController(service)

        val response = controller.installSkill(
            sourcePath = null,
            file = null,
            overwrite = false,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body!!
        assertEquals(false, body["success"])
        assertTrue((body["message"] as String).contains("Either 'path' or 'file'"))
    }

    @Test
    @DisplayName("installSkill returns 400 on IllegalArgumentException")
    fun testInstallSkillReturns400OnIllegalArgumentException(@TempDir tempDir: Path) = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.installSkill(any(), any()))
            .thenThrow(IllegalArgumentException("Invalid skill source"))
        val controller = SkillController(service)

        val response = controller.installSkill(
            sourcePath = tempDir.toString(),
            file = null,
            overwrite = false,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body!!
        assertEquals(false, body["success"])
    }

    @Test
    @DisplayName("installSkill returns 409 on conflict")
    fun testInstallSkillReturns409OnConflict(@TempDir tempDir: Path) = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.installSkill(any(), any()))
            .thenThrow(IllegalStateException("Skill already installed"))
        val controller = SkillController(service)

        val response = controller.installSkill(
            sourcePath = tempDir.toString(),
            file = null,
            overwrite = false,
        )

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = response.body!!
        assertEquals(false, body["success"])
    }

    // ---- uninstallSkill() ----

    @Test
    @DisplayName("uninstallSkill returns 200 when skill removed")
    fun testUninstallSkillReturns200WhenRemoved() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.uninstallSkill("web-scraping")).thenReturn(sampleInstallResult)
        val controller = SkillController(service)

        val response = controller.uninstallSkill("web-scraping")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        Mockito.verify(service).uninstallSkill("web-scraping")
        assertEquals(true, body["success"])
    }

    @Test
    @DisplayName("uninstallSkill returns 400 when skill not registered")
    fun testUninstallSkillReturns400WhenNotRegistered() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.uninstallSkill("nonexistent"))
            .thenThrow(IllegalArgumentException("Skill 'nonexistent' is not registered"))
        val controller = SkillController(service)

        val response = controller.uninstallSkill("nonexistent")

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ---- reloadSkill() ----

    @Test
    @DisplayName("reloadSkill returns 200 when reloaded")
    fun testReloadSkillReturns200WhenReloaded() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.reloadSkill("web-scraping")).thenReturn(true)
        val controller = SkillController(service)

        val response = controller.reloadSkill("web-scraping")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        Mockito.verify(service).reloadSkill("web-scraping")
        assertEquals(true, body["success"])
    }

    @Test
    @DisplayName("reloadSkill returns 404 when skill not found")
    fun testReloadSkillReturns404WhenNotFound() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.reloadSkill("nonexistent"))
            .thenThrow(IllegalArgumentException("Skill 'nonexistent' is not registered"))
        val controller = SkillController(service)

        val response = controller.reloadSkill("nonexistent")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
