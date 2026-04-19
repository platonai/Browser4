package ai.platon.pulsar.agentic.skills.examples

import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for example skills.
 */
class ExampleSkillsTest {

    private lateinit var registry: SkillRegistry
    private lateinit var context: SkillContext

    @BeforeEach
    fun setup() = runBlocking {
        registry = SkillRegistry.instance
        context = SkillContext(sessionId = "test-session-123")
        registry.clear(context)
    }

    @AfterEach
    fun cleanup() = runBlocking {
        registry.clear(context)
    }

    @Test
    @DisplayName("test WebScrapingSkill registration")
    fun testWebscrapingskillRegistration() = runBlocking {
        val skill = WebScrapingSkill()

        registry.register(skill, context)

        assertTrue(registry.contains("web-scraping"))
        assertEquals("Web Scraping", skill.metadata.name)
        assertEquals("1.0.0", skill.metadata.version)
        assertTrue(skill.metadata.tags.contains("scraping"))
    }

    @Test
    @DisplayName("test WebScrapingSkill execution with valid parameters")
    fun testWebscrapingskillExecutionWithValidParameters() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf(
            "url" to "https://example.com",
            "selector" to ".content"
        )

        val result = registry.execute("web-scraping", context, params)

        assertTrue(result.success)
        Assertions.assertNotNull(result.data)
        assertTrue(result.message!!.contains("Successfully extracted"))
    }

    @Test
    @DisplayName("test WebScrapingSkill execution with missing url parameter")
    fun testWebscrapingskillExecutionWithMissingUrlParameter() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf("selector" to ".content")

        val result = registry.execute("web-scraping", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Missing required parameter: url"))
    }

    @Test
    @DisplayName("test WebScrapingSkill execution with missing selector parameter")
    fun testWebscrapingskillExecutionWithMissingSelectorParameter() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf("url" to "https://example.com")

        val result = registry.execute("web-scraping", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Missing required parameter: selector"))
    }

    @Test
    @DisplayName("test WebScrapingSkill rejects invalid URL in onBeforeExecute")
    fun testWebscrapingskillRejectsInvalidUrlInOnbeforeexecute() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf(
            "url" to "invalid-url",
            "selector" to ".content"
        )

        val result = registry.execute("web-scraping", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("cancelled"))
    }

    @Test
    @DisplayName("test WebScrapingSkill with custom attributes")
    fun testWebscrapingskillWithCustomAttributes() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf(
            "url" to "https://example.com",
            "selector" to ".content",
            "attributes" to listOf("text", "href", "title")
        )

        val result = registry.execute("web-scraping", context, params)

        assertTrue(result.success)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        assertEquals(listOf("text", "href", "title"), data["attributes"])
    }

    @Test
    @DisplayName("test WebScrapingSkill sets shared resource on success")
    fun testWebscrapingskillSetsSharedResourceOnSuccess() = runBlocking {
        val skill = WebScrapingSkill()
        registry.register(skill, context)

        val params = mapOf(
            "url" to "https://example.com",
            "selector" to ".content"
        )

        registry.execute("web-scraping", context, params)

        Assertions.assertNotNull(context.getResource<Long>("last_scraping_success"))
    }

    @Test
    @DisplayName("test FormFillingSkill registration")
    fun testFormfillingskillRegistration() = runBlocking {
        val webScraping = WebScrapingSkill()
        val formFilling = FormFillingSkill()

        registry.register(webScraping, context)
        registry.register(formFilling, context)

        assertTrue(registry.contains("form-filling"))
        assertEquals("Form Filling", formFilling.metadata.name)
        assertTrue(formFilling.metadata.dependencies.contains("web-scraping"))
    }

    @Test
    @DisplayName("test FormFillingSkill cannot register without dependency")
    fun testFormfillingskillCannotRegisterWithoutDependency() = runBlocking {
        val skill = FormFillingSkill()

        val exception = assertThrows<IllegalStateException> {
            runBlocking { registry.register(skill, context) }
        }
        assertTrue(exception.message!!.contains("missing dependencies"))
    }

    @Test
    @DisplayName("test FormFillingSkill execution with valid parameters")
    fun testFormfillingskillExecutionWithValidParameters() = runBlocking {
        registry.register(WebScrapingSkill(), context)
        registry.register(FormFillingSkill(), context)

        val params = mapOf(
            "url" to "https://example.com/form",
            "formData" to mapOf(
                "name" to "John Doe",
                "email" to "john@example.com"
            )
        )

        val result = registry.execute("form-filling", context, params)

        assertTrue(result.success)
        assertTrue(result.message!!.contains("Form filled successfully"))

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val filledFields = data["filledFields"] as List<String>
        assertEquals(2, filledFields.size)
    }

    @Test
    @DisplayName("test FormFillingSkill execution with submit flag")
    fun testFormfillingskillExecutionWithSubmitFlag() = runBlocking {
        registry.register(WebScrapingSkill(), context)
        registry.register(FormFillingSkill(), context)

        val params = mapOf(
            "url" to "https://example.com/form",
            "formData" to mapOf("name" to "John"),
            "submit" to true
        )

        val result = registry.execute("form-filling", context, params)

        assertTrue(result.success)

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        assertEquals(true, data["submitted"])
    }

    @Test
    @DisplayName("test FormFillingSkill rejects empty form data")
    fun testFormfillingskillRejectsEmptyFormData() = runBlocking {
        registry.register(WebScrapingSkill(), context)
        registry.register(FormFillingSkill(), context)

        val params = mapOf(
            "url" to "https://example.com/form",
            "formData" to emptyMap<String, String>()
        )

        val result = registry.execute("form-filling", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("cancelled"))
    }

    @Test
    @DisplayName("test DataValidationSkill registration")
    fun testDataValidationSkillRegistration() = runBlocking {
        val skill = DataValidationSkill()

        registry.register(skill, context)

        assertTrue(registry.contains("data-validation"))
        assertEquals("Data Validation", skill.metadata.name)
        assertTrue(skill.metadata.tags.contains("validation"))
    }

    @Test
    @DisplayName("test DataValidationSkill validates email successfully")
    fun testDataValidationSkillValidatesEmailSuccessfully() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf("email" to "test@example.com"),
            "rules" to listOf("email")
        )

        val result = registry.execute("data-validation", context, params)

        assertTrue(result.success)
        assertTrue(result.message!!.contains("All validation rules passed"))
    }

    @Test
    @DisplayName("test DataValidationSkill rejects invalid email")
    fun testDataValidationSkillRejectsInvalidEmail() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf("email" to "invalid-email"),
            "rules" to listOf("email")
        )

        val result = registry.execute("data-validation", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Invalid email format"))
    }

    @Test
    @DisplayName("test DataValidationSkill validates required fields")
    fun testDataValidationSkillValidatesRequiredFields() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf("field1" to "value1", "field2" to "value2"),
            "rules" to listOf("required")
        )

        val result = registry.execute("data-validation", context, params)

        assertTrue(result.success)
    }

    @Test
    @DisplayName("test DataValidationSkill rejects empty required fields")
    fun testDataValidationSkillRejectsEmptyRequiredFields() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf("field1" to "", "field2" to "value2"),
            "rules" to listOf("required")
        )

        val result = registry.execute("data-validation", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Required fields are missing"))
    }

    @Test
    @DisplayName("test DataValidationSkill handles unknown rules")
    fun testDataValidationSkillHandlesUnknownRules() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf("field" to "value"),
            "rules" to listOf("unknown-rule")
        )

        val result = registry.execute("data-validation", context, params)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Unknown validation rule"))
    }

    @Test
    @DisplayName("test DataValidationSkill with multiple rules")
    fun testDataValidationSkillWithMultipleRules() = runBlocking {
        val skill = DataValidationSkill()
        registry.register(skill, context)

        val params = mapOf(
            "data" to mapOf(
                "email" to "test@example.com",
                "name" to "John"
            ),
            "rules" to listOf("email", "required")
        )

        val result = registry.execute("data-validation", context, params)

        assertTrue(result.success)
    }

    @Test
    @DisplayName("test skill tool call specifications")
    fun testSkillToolCallSpecifications() {
        val webScraping = WebScrapingSkill()
        val formFilling = FormFillingSkill()
        val dataValidation = DataValidationSkill()

        assertTrue(webScraping.toolSpec.isNotEmpty())
        assertEquals("skill.debug.scraping", webScraping.toolSpec[0].domain)
        assertEquals("extract", webScraping.toolSpec[0].method)

        assertTrue(formFilling.toolSpec.isNotEmpty())
        assertEquals("skill.form", formFilling.toolSpec[0].domain)
        assertEquals("fill", formFilling.toolSpec[0].method)

        assertTrue(dataValidation.toolSpec.isEmpty())
    }
}
