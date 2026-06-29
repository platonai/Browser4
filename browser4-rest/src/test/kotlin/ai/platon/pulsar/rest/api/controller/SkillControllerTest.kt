package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agentic.skills.SkillRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

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

    // ---- listSkills() ----

    @Test
    fun `listSkills returns skill list from service`() {
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
    fun `listSkills returns empty list when no skills`() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.listSkills()).thenReturn(emptyList())
        val controller = SkillController(service)

        val response = controller.listSkills()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isEmpty())
    }

    // ---- getSkill() ----

    @Test
    fun `getSkill returns 200 when skill found`() {
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
    fun `getSkill returns 404 when skill not found`() {
        val service = Mockito.mock(SkillService::class.java)
        Mockito.`when`(service.getSkill("nonexistent")).thenReturn(null)
        val controller = SkillController(service)

        val response = controller.getSkill("nonexistent")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        Mockito.verify(service).getSkill("nonexistent")
    }
}
