package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

/**
 * The REST HTTP layer runs on Jackson 3 (`tools.jackson`), Spring Boot 4's default
 * HTTP mapper, and Boot populates it from the ServiceLoader
 * (`spring.jackson.find-and-add-modules` defaults to `true`).
 *
 * That makes the Jackson 3 Kotlin module a hard requirement for every Kotlin
 * `@RequestBody` DTO with a **required** constructor parameter: without it,
 * `JsonMapper` reports `Cannot construct instance of … (no Creators, like default
 * constructor, exist)` and the endpoint answers 500. That regression shipped once
 * (`/api/conversations/about`, `POST /api/commands/json`) while the Jackson 2
 * module was present, so the mapping is asserted here against Boot's own
 * auto-configured mapper rather than a hand-built one.
 *
 * The DTOs are exercised through the auto-configured `JsonMapper`, not through a
 * web slice: this is a classpath/module-registration contract, and a full
 * `@WebMvcTest` would add a servlet stack for no extra signal.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("REST Kotlin DTO JSON binding")
class KotlinDtoJsonBindingTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))

    @Test
    @DisplayName("Boot's auto-configured Jackson 3 mapper has the Kotlin module")
    fun autoConfiguredMapperHasTheKotlinModule() {
        runner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val moduleNames = mapper.registeredModules().map { it.javaClass.name }
            assertTrue(
                moduleNames.any { it == "tools.jackson.module.kotlin.KotlinModule" },
                "the Jackson 3 Kotlin module must be registered, found: $moduleNames"
            )
        }
    }

    @Test
    @DisplayName("PromptRequest with a required url binds from JSON")
    fun promptRequestBindsFromJson() {
        runner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val request = mapper.readValue(
                """{"url":"https://example.com","prompt":"say hi","args":"-refresh","actions":["scroll down"]}""",
                PromptRequest::class.java
            )

            assertEquals("https://example.com", request.url)
            assertEquals("say hi", request.prompt)
            assertEquals("-refresh", request.args)
            assertEquals(listOf("scroll down"), request.actions)
        }
    }

    @Test
    @DisplayName("PageVisitRequest (CommandRequest) binds its page actions from JSON")
    fun pageVisitRequestBindsPageActionsFromJson() {
        runner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            val request = mapper.readValue(
                """{"url":"https://example.com","onPageReadyActions":["click the button"],"async":true}""",
                PageVisitRequest::class.java
            )

            assertEquals("https://example.com", request.url)
            assertEquals(listOf("click the button"), request.onPageReadyActions)
            assertTrue(request.isAsync())
            assertTrue(request.hasAction())
        }
    }
}
