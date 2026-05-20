package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.skeleton.browser.driver.WebDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BrowserTabToolExecutorTest {
	private val executor = BrowserTabToolExecutor()

	@Test
	fun `type accepts focused element text only`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)

			executor.callFunctionOn(
				ToolCall("tab", "type", mutableMapOf<String, Any?>("text" to "hello")),
				driver
			)

			verify(driver).type("hello", null)
		}
	}

	@Test
	fun `type still accepts selector and text`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)

			executor.callFunctionOn(
				ToolCall("tab", "type", mutableMapOf<String, Any?>("selector" to "#q", "text" to "hello")),
				driver
			)

			verify(driver).type("hello", "#q")
		}
	}

	@Test
	fun `press accepts focused element key only`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)

			executor.callFunctionOn(
				ToolCall("tab", "press", mutableMapOf<String, Any?>("key" to "Enter")),
				driver
			)

			verify(driver).press("Enter", null)
		}
	}

	@Test
	fun `press still accepts selector and key`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)

			executor.callFunctionOn(
				ToolCall("tab", "press", mutableMapOf<String, Any?>("selector" to "#q", "key" to "Enter")),
				driver
			)

			verify(driver).press("Enter", "#q")
		}
	}

	@Test
	fun `help advertises selector optional type and press`() {
		val typeHelp = executor.help("type")
		val pressHelp = executor.help("press")

		assertTrue(typeHelp.contains("tab.type(text: String)"))
		assertTrue(typeHelp.contains("selector: String?"))
		assertTrue(pressHelp.contains("tab.press(key: String)"))
		assertTrue(pressHelp.contains("selector: String?"))
	}

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

	@Test
	fun `evaluateValue accepts element selector and expression`() {
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
						"expression" to "(element) => element.textContent"
					)
				),
				driver
			)

			assertEquals("other page", result.value)
			verify(driver).evaluateValue("#page-marker", "(element) => element.textContent")
		}
	}

	@Test
	fun `eval accepts page expression`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)
			`when`(driver.evaluateValue("document.title")).thenReturn("Browser4 CLI Other Fixture")

			val result = executor.callFunctionOn(
				ToolCall("tab", "eval", mutableMapOf<String, Any?>("expression" to "document.title")),
				driver
			)

			assertEquals("Browser4 CLI Other Fixture", result.value)
			verify(driver).evaluateValue("document.title")
		}
	}

	@Test
	fun `eval accepts element selector and expression`() {
		runBlocking {
			val driver = Mockito.mock(WebDriver::class.java)
			`when`(driver.evaluateValue("#page-marker", "(element) => element.textContent"))
				.thenReturn("other page")

			val result = executor.callFunctionOn(
				ToolCall(
					"tab",
					"eval",
					mutableMapOf<String, Any?>(
						"selector" to "#page-marker",
						"expression" to "(element) => element.textContent"
					)
				),
				driver
			)

			assertEquals("other page", result.value)
			verify(driver).evaluateValue("#page-marker", "(element) => element.textContent")
		}
	}
}
