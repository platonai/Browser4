package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `AbstractBrowser.listDrivers()` is `ConcurrentHashMap.values.toList()` — hash
 * order, not insertion and not Chrome's tab-strip order. Every tab index the
 * tool interface publishes or resolves has to come from this stable ordering, or
 * an index read from one listing addresses another tab in the next call.
 */
@DisplayName("Tab ordering")
class TabActivityTest {

    private fun tab(id: Int, guid: String): AbstractWebDriver {
        val driver = mockk<AbstractWebDriver>(relaxed = true)
        every { driver.id } returns id
        every { driver.guid } returns guid
        return driver
    }

    private fun browserReturning(vararg drivers: AbstractWebDriver): AbstractBrowser {
        val browser = mockk<AbstractBrowser>(relaxed = true)
        coEvery { browser.listDrivers() } returns drivers.toList()
        return browser
    }

    @Test
    @DisplayName("tabs are ordered by creation id, not by the driver map's order")
    fun orderedByCreationIdNotMapOrder() = runBlocking {
        val c = tab(3003, "GUID-C")
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")

        val ordered = TabActivity.orderedTabDrivers(browserReturning(c, a, b))

        assertEquals(listOf("GUID-A", "GUID-B", "GUID-C"), ordered.map { it.guid })
    }

    @Test
    @DisplayName("the same tab set orders identically however the map enumerates it")
    fun orderIsReproducibleAcrossCalls() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        val c = tab(3003, "GUID-C")

        val first = TabActivity.orderedTabDrivers(browserReturning(c, b, a)).map { it.guid }
        val second = TabActivity.orderedTabDrivers(browserReturning(b, a, c)).map { it.guid }

        assertEquals(first, second)
    }

    @Test
    @DisplayName("a tab created later sorts last and survivors keep their positions")
    fun additionsAppendAndSurvivorsKeepOrder() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        val d = tab(4004, "GUID-D")

        assertEquals(
            listOf("GUID-A", "GUID-B", "GUID-D"),
            TabActivity.orderedTabDrivers(browserReturning(d, b, a)).map { it.guid },
        )
    }

    @Test
    @DisplayName("only order is defined here: which tabs are listed stays the browser's answer")
    fun onlyOrderChanges() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")

        val ordered = TabActivity.orderedTabDrivers(browserReturning(b, a))

        assertEquals(setOf("GUID-A", "GUID-B"), ordered.map { it.guid }.toSet())
        assertEquals(2, ordered.size)
    }
}
