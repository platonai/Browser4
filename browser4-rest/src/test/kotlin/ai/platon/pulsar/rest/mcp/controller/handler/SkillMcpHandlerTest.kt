package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class SkillMcpHandlerTest {

    private val objectMapper = ObjectMapper()
    private val sampleSummary = SkillRegistry.SkillSummary(
        id = "web-scraping",
        name = "Web Scraping",
        description = "Extract data from web pages",
        version = "1.0.0",
        tags = setOf("scraping"),
    )
    private val sampleDetail = SkillService.SkillDetail(
        id = "web-scraping",
        name = "Web Scraping",
        version = "1.0.0",
        description = "Extract data from web pages",
        author = "platon",
        tags = listOf("scraping"),
        dependencies = emptyList(),
        skillMd = "# Web Scraping\n\nExtract data from web pages.",
        scriptsPath = null,
        referencesPath = null,
        assetsPath = null,
        origin = null,
    )

    // ---- TOOL_NAMES ----

    @Test
    @DisplayName("TOOL_NAMES contains all five skill tools")
    fun testToolNamesContainsAllFiveSkillTools() {
        val names = SkillMcpHandler.TOOL_NAMES
        assertTrue(names.contains("skill_list"))
        assertTrue(names.contains("skill_info"))
        assertTrue(names.contains("skill_install"))
        assertTrue(names.contains("skill_uninstall"))
        assertTrue(names.contains("skill_reload"))
        assertEquals(5, names.size)
    }

    // ---- handleSkillList ----

    @Test
    @DisplayName("handleSkillList returns summaries as JSON")
    fun testHandleSkillListReturnsSummariesAsJson() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.listSkills()).thenReturn(listOf(sampleSummary))
        val handler = SkillMcpHandler(service, objectMapper)

        val response = handler.handleSkillList()

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertFalse(body.isError)
        val text = body.content!![0].text
        assertTrue(text.contains("web-scraping"), "Response should contain skill id: $text")
        assertTrue(text.contains("Extract data"), "Response should contain description: $text")
        Mockito.verify(service).listSkills()
    }

    @Test
    @DisplayName("handleSkillList returns empty array when no skills")
    fun testHandleSkillListReturnsEmptyArrayWhenNoSkills() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.listSkills()).thenReturn(emptyList())
        val handler = SkillMcpHandler(service, objectMapper)

        val response = handler.handleSkillList()

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertFalse(body.isError)
        assertEquals("[]", body.content!![0].text.trim { it <= ' ' })
    }

    // ---- handleSkillInfo ----

    @Test
    @DisplayName("handleSkillInfo returns detail when skill found")
    fun testHandleSkillInfoReturnsDetailWhenFound() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.getSkill("web-scraping")).thenReturn(sampleDetail)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_info",
            arguments = mapOf("id" to "web-scraping"),
        )
        val response = handler.handleSkillInfo(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertFalse(body.isError)
        val text = body.content!![0].text
        assertTrue(text.contains("web-scraping"), "Response should contain skill id: $text")
        assertTrue(text.contains("Web Scraping"), "Response should contain skill name: $text")
        Mockito.verify(service).getSkill("web-scraping")
    }

    @Test
    @DisplayName("handleSkillInfo returns error when id parameter missing")
    fun testHandleSkillInfoReturnsErrorWhenIdMissing() {
        val service = Mockito.mock(SkillService::class.java)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_info",
            arguments = emptyMap(),
        )
        val response = handler.handleSkillInfo(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.isError)
        assertTrue(body.content!![0].text.contains("Missing required parameter"))
    }

    @Test
    @DisplayName("handleSkillInfo returns error when skill not found")
    fun testHandleSkillInfoReturnsErrorWhenNotFound() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.getSkill("nonexistent")).thenReturn(null)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_info",
            arguments = mapOf("id" to "nonexistent"),
        )
        val response = handler.handleSkillInfo(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.isError)
        assertTrue(body.content!![0].text.contains("not found"))
    }

    // ---- handleSkillInstall ----

    @Test
    @DisplayName("handleSkillInstall returns error when path parameter missing")
    fun testHandleSkillInstallReturnsErrorWhenPathMissing() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_install",
            arguments = emptyMap(),
        )
        val response = handler.handleSkillInstall(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.isError)
        assertTrue(body.content!![0].text.contains("Missing required parameter"))
    }

    // ---- handleSkillUninstall ----

    @Test
    @DisplayName("handleSkillUninstall returns error when id parameter missing")
    fun testHandleSkillUninstallReturnsErrorWhenIdMissing() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_uninstall",
            arguments = emptyMap(),
        )
        val response = handler.handleSkillUninstall(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.isError)
        assertTrue(body.content!![0].text.contains("Missing required parameter"))
    }

    // ---- handleSkillReload ----

    @Test
    @DisplayName("handleSkillReload returns error when id parameter missing")
    fun testHandleSkillReloadReturnsErrorWhenIdMissing() = kotlinx.coroutines.runBlocking {
        val service = Mockito.mock(SkillService::class.java)
        val handler = SkillMcpHandler(service, objectMapper)

        val request = MCPToolCallRequest(
            tool = "skill_reload",
            arguments = emptyMap(),
        )
        val response = handler.handleSkillReload(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertTrue(body.isError)
        assertTrue(body.content!![0].text.contains("Missing required parameter"))
    }
}
