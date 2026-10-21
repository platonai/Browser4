package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.api.WebDriver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The page status a task reports must be the status the page really returned, or
 * nothing at all — never the model's `201 / SC_CREATED` default, which every
 * agent and loop record used to carry next to a message saying the page had
 * loaded with HTTP 200.
 */
@DisplayName("Document HTTP status probe")
class DocumentHttpStatusTest {

    private fun driverReturning(value: Any?): WebDriver {
        val driver = mockk<WebDriver>(relaxed = true)
        coEvery { driver.evaluateValue(DocumentHttpStatus.RESPONSE_STATUS_EXPRESSION) } returns value
        return driver
    }

    @Test
    @DisplayName("a page that loaded with 200 reports 200")
    fun reportsTheObservedStatus() = runBlocking {
        assertEquals(200, DocumentHttpStatus.of(driverReturning(200)))
        assertEquals(404, DocumentHttpStatus.of(driverReturning(404)))
        // A real 201 from the PAGE is still a real status (distinguishable from unknown).
        assertEquals(201, DocumentHttpStatus.of(driverReturning(201)))
    }

    @Test
    @DisplayName("a browser that cannot report a status yields null, not a made-up code")
    fun unobservableStatusIsNull() = runBlocking {
        // Chrome < 109, a non-navigation document, a wedged page: the page itself
        // answers null.
        assertNull(DocumentHttpStatus.of(driverReturning(null)))
        // No driver at all (a coding task, a task that never opened a page).
        assertNull(DocumentHttpStatus.of(null))
    }

    @Test
    @DisplayName("a value that is not a plausible HTTP status is not reported as one")
    fun implausibleValuesAreNotStatuses() = runBlocking {
        assertNull(DocumentHttpStatus.of(driverReturning("200")), "a string is not a status")
        assertNull(DocumentHttpStatus.of(driverReturning(0)), "0 is the unknown marker, not a status")
        assertNull(DocumentHttpStatus.of(driverReturning(99)), "below the HTTP range")
        assertNull(DocumentHttpStatus.of(driverReturning(600)), "above the HTTP range")
    }

    @Test
    @DisplayName("a failing read is 'not observed', it never fails the task")
    fun failingReadIsNotObserved() = runBlocking {
        val driver = mockk<WebDriver>(relaxed = true)
        coEvery { driver.evaluateValue(any<String>()) } throws IllegalStateException("page is gone")

        assertNull(DocumentHttpStatus.of(driver))
    }

    @Test
    @DisplayName("unknown is distinguishable from a real 201")
    fun unknownIsNotARealStatus() = runBlocking {
        val unknown = DocumentHttpStatus.of(driverReturning(null))
        val real = DocumentHttpStatus.of(driverReturning(201))

        assertNull(unknown)
        assertEquals(201, real)
        assertNotEquals(real, unknown)
    }
}
