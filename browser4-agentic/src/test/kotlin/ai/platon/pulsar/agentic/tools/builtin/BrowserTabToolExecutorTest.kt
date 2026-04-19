package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BrowserTabToolExecutorTest {
	private val executor = BrowserTabToolExecutor()

	@Test
	fun `evaluateValue accepts page expression`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)
			`when`(driver.evaluateValue("document.title")).thenReturn("Browser4 CLI Other Fixture")

			val result = executor.callFunctionOn(
				ToolCall("tab", "evaluateValue", mutableMapOf<String, Any?>("expression" to "document.title")),
				driver
			)

			assertEquals("Browser4 CLI Other Fixture", result.value)
			verify(driver).evaluateValue("document.title")
		}
	}

	@Test
	fun `evaluateValue accepts element selector and function declaration`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)
			`when`(driver.evaluateValue("#page-marker", "(element) => element.textContent"))
				.thenReturn("other page")

			val result = executor.callFunctionOn(
				ToolCall(
					"tab",
					"evaluateValue",
					mutableMapOf<String, Any?>(
						"selector" to "#page-marker",
						"functionDeclaration" to "(element) => element.textContent"
					)
				),
				driver
			)

			assertEquals("other page", result.value)
			verify(driver).evaluateValue("#page-marker", "(element) => element.textContent")
		}
	}
}
