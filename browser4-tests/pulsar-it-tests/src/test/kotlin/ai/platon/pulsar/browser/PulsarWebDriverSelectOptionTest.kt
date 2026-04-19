package ai.platon.pulsar.browser

import ai.platon.pulsar.WebDriverTestBase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PulsarWebDriverSelectOptionTest : WebDriverTestBase() {
    override val webDriverService get() = FastWebDriverService(browserFactory)

    @Test
    fun testSelectOption() = runEnhancedWebDriverTest("about:blank", browser) { driver ->
        val html = """
            <select id="colors">
                <option value="red">Red</option>
                <option value="green">Green</option>
                <option value="blue">Blue</option>
            </select>
            <select id="fruits" multiple>
                <option value="apple">Apple</option>
                <option value="banana">Banana</option>
                <option value="cherry">Cherry</option>
            </select>
        """.trimIndent()

        // Load content
        driver.navigate("about:blank")
        // Use evaluate to set innerHTML.
        // Note: Kotlin string interpolation might conflict with JS template literals if not careful.
        // Using backticks for JS string to support multiline.
        val js = "document.body.innerHTML = `$html`"
        driver.evaluate(js)

        // Wait a bit for rendering (though sync)
        driver.waitForSelector("select#colors")

        // Debug: check body content
        val body = driver.evaluate("document.body.innerHTML")
        println("Body content: $body")

        // Debug: check element exists
        val exists = driver.evaluate("!!document.querySelector('select#colors')")
        println("Element exists: $exists")

        // Test single select by value "blue"
        var selected = driver.selectOption("select#colors", "blue")
        println("Selected: $selected")
        assertEquals("blue", selected)

        // Verify in DOM
        var domValue = driver.evaluate("document.querySelector('#colors').value")
        assertEquals("blue", domValue)

        // Test single select by label "Green"
        selected = driver.selectOption("select#colors", "Green")
        assertEquals("green", selected)

        domValue = driver.evaluate("document.querySelector('#colors').value")
        assertEquals("green", domValue)

        // Test multiple select
        val fruits = listOf("apple", "cherry")
        var selectedList = driver.selectOption("select#fruits", fruits)
        println("Selected List: $selectedList")
        // Order might depend on implementation or DOM order.
        assertEquals(2, selectedList.size, "Selected list size mismatch")
        assertTrue(selectedList.contains("apple"), "Selected list should contain apple")
        assertTrue(selectedList.contains("cherry"), "Selected list should contain cherry")

        // Verify in DOM using simpler approach
        val val1 = driver.evaluate("document.querySelector('#fruits option[value=apple]').selected")
        val val2 = driver.evaluate("document.querySelector('#fruits option[value=cherry]').selected")
        val val3 = driver.evaluate("document.querySelector('#fruits option[value=banana]').selected")

        println("Apple selected: $val1")
        println("Cherry selected: $val2")
        println("Banana selected: $val3")

        assertEquals(true, val1, "Apple should be selected")
        assertEquals(true, val2, "Cherry should be selected")
        assertEquals(false, val3, "Banana should not be selected")

        // Test multiple select with clearing previous selection
        // Select "banana"
        selectedList = driver.selectOption("select#fruits", listOf("banana"))
        println("Selected List 2: $selectedList")
        assertEquals(1, selectedList.size, "Selected list 2 size mismatch")
        assertEquals("banana", selectedList[0], "Selected item should be banana")

        val val4 = driver.evaluate("document.querySelector('#fruits option[value=banana]').selected")
        println("Banana selected 2: $val4")
        assertEquals(true, val4, "Banana should be selected 2")

        val val5 = driver.evaluate("document.querySelector('#fruits option[value=apple]').selected")
        assertEquals(false, val5, "Apple should not be selected 2")
    }
}
