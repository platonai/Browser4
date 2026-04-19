package ai.platon.browser4.driver.chrome.dom

import ai.platon.pulsar.FastWebDriverService
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("E2ETest")
class PulsarWebDriverScrollTests : WebDriverTestBase() {

    override val webDriverService get() = FastWebDriverService(browserFactory)

    @Test
    @DisplayName("test scrollBy")
    fun testScrollBy() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        val scrollY = driver.scrollBy(200.0, smooth = true)

        assertEquals(200.0, scrollY, 1.0)
        assertEquals(200.0, driver.evaluate("window.scrollY", 200.0), 1.0)
    }

    // Helpers & data structures for extended scroll tests
    data class ScrollMetrics(
        val scrollY: Double,
        val viewportHeight: Double,
        val totalHeight: Double,
        val maxScrollY: Double
    )

    companion object {
        private const val SMALL_TOL = 1.0
        private const val MEDIUM_TOL = 3.0
        private const val LARGE_TOL = 5.0
    }

    private suspend fun getScrollMetrics(driver: WebDriver): ScrollMetrics {
        val scrollY = driver.evaluate("window.scrollY", 0.0)
        val viewportHeight = driver.evaluate("window.innerHeight", 0.0)
        val totalHeight = driver.evaluate(
            "Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)",
            0.0
        )
        val maxScrollY = (totalHeight - viewportHeight).let { if (it < 0) 0.0 else it }
        return ScrollMetrics(scrollY, viewportHeight, totalHeight, maxScrollY)
    }

    private suspend fun ensureTallPage(driver: WebDriver, minHeight: Double) {
        val totalHeight = driver.evaluate(
            "Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)",
            0.0
        )
        if (totalHeight >= minHeight) return
        driver.evaluate(
            """(function(minH){
              const body = document.body;
              let h = Math.max(document.documentElement.scrollHeight, body.scrollHeight);
              let i = 0;
              while(h < minH && i < 300){
                const d = document.createElement('div');
                d.style.height='250px';
                d.style.border='1px solid #eee';
                d.textContent='pad-' + i;
                body.appendChild(d);
                h = Math.max(document.documentElement.scrollHeight, body.scrollHeight);
                i++;
              }
              return h;
            })(${minHeight})""".trimIndent(),
            0.0
        )
    }

    private fun assertAlmostEquals(expected: Double, actual: Double, tol: Double, msg: String? = null) {
        assertTrue(abs(expected - actual) <= tol, msg ?: "Expected ~${expected} ±${tol}, actual=${actual}")
    }

    // Extended test cases for scrollBy covering edge conditions and behavior nuances

    @Test
    @DisplayName("scrollBy zero delta at top is idempotent")
    fun scrollByZeroDeltaAtTopIsIdempotent() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        val m0 = getScrollMetrics(driver)
        assertEquals(0.0, m0.scrollY, SMALL_TOL)
        val y = driver.scrollBy(0.0, smooth = true)
        val m1 = getScrollMetrics(driver)
        assertAlmostEquals(0.0, y, SMALL_TOL)
        assertAlmostEquals(m0.scrollY, m1.scrollY, SMALL_TOL)
        assertEquals(driver.evaluate("window.scrollX", 0.0), 0.0, SMALL_TOL)
    }

    @Test
    @DisplayName("scrollBy small positive single step")
    fun scrollBySmallPositiveSingleStep() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        val m0 = getScrollMetrics(driver)
        assertEquals(0.0, m0.scrollY, MEDIUM_TOL)
        val delta = 5.0
        val y = driver.scrollBy(delta, smooth = true)
        val m1 = getScrollMetrics(driver)
        assertAlmostEquals(delta, y, MEDIUM_TOL)
        assertAlmostEquals(delta, m1.scrollY, MEDIUM_TOL)
    }

    @Test
    @DisplayName("scrollBy negative at top clamps to zero")
    fun scrollByNegativeAtTopClampsToZero() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        val m0 = getScrollMetrics(driver)
        assertEquals(0.0, m0.scrollY, SMALL_TOL)
        val y = driver.scrollBy(-200.0, smooth = true)
        assertAlmostEquals(0.0, y, SMALL_TOL)
        val m1 = getScrollMetrics(driver)
        assertAlmostEquals(0.0, m1.scrollY, SMALL_TOL)
    }

    @Test
    @DisplayName("scrollBy negative mid page decreases position")
    fun scrollByNegativeMidPageDecreasesPosition() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            driver.scrollBy(800.0, smooth = true)
            val mBefore = getScrollMetrics(driver)
            val y2 = driver.scrollBy(-400.0, smooth = true)
            val mAfter = getScrollMetrics(driver)
            assertTrue(mAfter.scrollY < mBefore.scrollY + SMALL_TOL)
            assertAlmostEquals(mBefore.scrollY - 400.0, y2, LARGE_TOL)
        }

    @Test
    @DisplayName("scrollBy overscroll bottom clamps")
    fun scrollByOverscrollBottomClamps() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        ensureTallPage(driver, 4000.0)
        val y = driver.scrollBy(20000.0, smooth = true)
        val m = getScrollMetrics(driver)
        assertAlmostEquals(m.maxScrollY, y, LARGE_TOL)
        assertAlmostEquals(m.maxScrollY, m.scrollY, LARGE_TOL)
    }

    @Test
    @DisplayName("scrollBy overscroll upward from bottom clamps to zero")
    fun scrollByOverscrollUpwardFromBottomClampsToZero() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 4000.0)
            driver.scrollBy(20000.0, smooth = true)
            val y = driver.scrollBy(-20000.0, smooth = true)
            assertAlmostEquals(0.0, y, SMALL_TOL)
            val m = getScrollMetrics(driver)
            assertAlmostEquals(0.0, m.scrollY, SMALL_TOL)
        }

    @Test
    @DisplayName("scrollBy smooth vs instant produce similar final position")
    fun scrollBySmoothVsInstantProduceSimilarFinalPosition() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            // Compare from the same baseline; reset to top between runs
            val ySmooth = driver.scrollBy(600.0, smooth = true)
            driver.scrollToTop()
            val yInstant = driver.scrollBy(600.0, smooth = false)
            assertTrue(abs(yInstant - ySmooth) <= LARGE_TOL, "Difference ${abs(yInstant - ySmooth)} too large")
        }

    @Test
    @DisplayName("scrollBy sequential cumulative small deltas")
    fun scrollBySequentialCumulativeSmallDeltas() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            var expected = 0.0
            repeat(5) {
                expected += 120.0
                val y = driver.scrollBy(120.0, smooth = true)
                assertAlmostEquals(expected, y, LARGE_TOL)
            }
            val m = getScrollMetrics(driver)
            assertAlmostEquals(expected, m.scrollY, LARGE_TOL)
        }

    @Test
    @DisplayName("scrollBy clamp boundary equality near bottom")
    fun scrollByClampBoundaryEqualityNearBottom() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 5000.0)
            val m0 = getScrollMetrics(driver)
            val targetDelta = m0.maxScrollY * 0.9
            driver.scrollBy(targetDelta, smooth = true)
            val mMid = getScrollMetrics(driver)
            val y2 = driver.scrollBy(mMid.maxScrollY * 0.5, smooth = true) // should clamp
            val m2 = getScrollMetrics(driver)
            assertAlmostEquals(m2.maxScrollY, y2, LARGE_TOL)
            assertAlmostEquals(m2.maxScrollY, m2.scrollY, LARGE_TOL)
        }

    @Test
    @DisplayName("scrollBy negative single pixel")
    fun scrollByNegativeSinglePixel() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        ensureTallPage(driver, 3000.0)
        driver.scrollBy(300.0, smooth = true)
        val before = getScrollMetrics(driver)
        val y = driver.scrollBy(-1.0, smooth = true)
        val after = getScrollMetrics(driver)
        assertTrue(after.scrollY <= before.scrollY + SMALL_TOL)
        assertAlmostEquals(before.scrollY - 1.0, y, MEDIUM_TOL)
    }

    @Test
    @DisplayName("scrollBy rapid smooth sequence stability")
    fun scrollByRapidSmoothSequenceStability() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            val deltas = listOf(50.0, 75.0, 100.0, -30.0, 60.0)
            var expected = 0.0
            deltas.forEach { d ->
                expected += d
                expected = expected.coerceIn(0.0, getScrollMetrics(driver).maxScrollY)
                val y = driver.scrollBy(d, smooth = true)
                assertAlmostEquals(expected, y, LARGE_TOL)
            }
            val m = getScrollMetrics(driver)
            assertAlmostEquals(expected, m.scrollY, LARGE_TOL)
        }

    @Test
    @DisplayName("scrollBy return value consistency")
    fun scrollByReturnValueConsistency() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        ensureTallPage(driver, 3000.0)
        val y = driver.scrollBy(450.0, smooth = true)
        val reported = driver.evaluate("window.scrollY", 0.0)
        assertAlmostEquals(reported, y, MEDIUM_TOL)
    }

    @Test
    @DisplayName("scrollBy concurrent smooth calls final equals one of targets")
    fun scrollByConcurrentSmoothCallsFinalEqualsOneOfTargets() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            val deltas = listOf(150.0, 200.0, 250.0)
            val results = coroutineScope {
                val deferred = deltas.map { d -> async { driver.scrollBy(d, smooth = true) } }
                deferred.awaitAll()
            }
            val m = getScrollMetrics(driver)
            // Concurrent smooth scrolls are not serialized; last-wins behavior sets a final target
            assertTrue(
                results.any { abs(it - m.scrollY) <= LARGE_TOL },
                "Final scrollY ${m.scrollY} should match one of concurrent targets ${results}"
            )
            assertTrue(m.scrollY in 0.0..m.maxScrollY + SMALL_TOL)
        }

    @Test
    @DisplayName("scrollBy on short page has no effect")
    fun scrollByOnShortPageHasNoEffect() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        // Shrink page content to be shorter than viewport
        driver.evaluate("document.body.innerHTML='<div style=\"height:50px\">Short</div>'", 0.0)
        val m0 = getScrollMetrics(driver)
        assertEquals(0.0, m0.maxScrollY, SMALL_TOL)
        val y1 = driver.scrollBy(500.0, smooth = true)
        val m1 = getScrollMetrics(driver)
        assertAlmostEquals(0.0, y1, SMALL_TOL)
        assertAlmostEquals(0.0, m1.scrollY, SMALL_TOL)
    }

    private suspend fun getRawTotalHeight(driver: WebDriver): Double {
        return driver.evaluate("Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)", 0.0)
    }

    @Test
    @DisplayName("scrollBy default parameters move 200px")
    fun scrollByDefaultParametersMove200px() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        ensureTallPage(driver, 1200.0)
        val m0 = getScrollMetrics(driver)
        assertAlmostEquals(0.0, m0.scrollY, SMALL_TOL)
        val y = driver.scrollBy() // default 200.0 smooth=true
        val m1 = getScrollMetrics(driver)
        assertAlmostEquals(200.0, y, LARGE_TOL)
        assertAlmostEquals(200.0, m1.scrollY, LARGE_TOL)
    }

    @Test
    @DisplayName("scrollBy extremely tall page clamps to 15000")
    fun scrollByExtremelyTallPageClampsTo15000() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            // Create a page taller than 15000 to trigger internal clamp logic
            ensureTallPage(driver, 17000.0)
            val rawTotal = getRawTotalHeight(driver)
            assertTrue(rawTotal > 15000.0, "Raw total height should exceed clamp threshold: $rawTotal")
            val y = driver.scrollBy(50000.0, smooth = true) // attempt far overscroll
            val m1 = getScrollMetrics(driver)
            val viewportH = m1.viewportHeight
            val expectedClampedMax = (15000.0 - viewportH).coerceAtLeast(0.0)
            assertAlmostEquals(expectedClampedMax, y, LARGE_TOL)
            assertAlmostEquals(expectedClampedMax, m1.scrollY, LARGE_TOL)
            // Ensure raw possible max (rawTotal - viewportH) is greater, proving clamp applied
            assertTrue(
                (rawTotal - viewportH) - expectedClampedMax > 10.0,
                "Clamp difference too small: rawTotal=$rawTotal viewport=$viewportH"
            )
        }

    @Test
    @DisplayName("scrollBy bottom small positive delta no-op")
    fun scrollByBottomSmallPositiveDeltaNoOp() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 4000.0)
            driver.scrollBy(100000.0, smooth = true) // go to bottom
            val mBottom = getScrollMetrics(driver)
            val y2 = driver.scrollBy(50.0, smooth = true) // small positive should remain
            val mAfter = getScrollMetrics(driver)
            assertAlmostEquals(mBottom.scrollY, y2, SMALL_TOL)
            assertAlmostEquals(mBottom.scrollY, mAfter.scrollY, SMALL_TOL)
        }

    @Test
    @DisplayName("scrollBy mid page large negative overscroll clamps to zero")
    fun scrollByMidPageLargeNegativeOverscrollClampsToZero() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 3000.0)
            driver.scrollBy(1200.0, smooth = true)
            val mMid = getScrollMetrics(driver)
            assertTrue(mMid.scrollY > 0.0)
            val y = driver.scrollBy(-5000.0, smooth = true) // big negative overscroll
            val mAfter = getScrollMetrics(driver)
            assertAlmostEquals(0.0, y, SMALL_TOL)
            assertAlmostEquals(0.0, mAfter.scrollY, SMALL_TOL)
        }

    @Test
    @DisplayName("scrollBy zero delta mid page unchanged")
    fun scrollByZeroDeltaMidPageUnchanged() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        ensureTallPage(driver, 2500.0)
        driver.scrollBy(800.0, smooth = true)
        val mBefore = getScrollMetrics(driver)
        val y = driver.scrollBy(0.0, smooth = true)
        val mAfter = getScrollMetrics(driver)
        assertAlmostEquals(mBefore.scrollY, y, SMALL_TOL)
        assertAlmostEquals(mBefore.scrollY, mAfter.scrollY, SMALL_TOL)
    }

    @Test
    @DisplayName("scrollBy cumulative beyond clamp stops increasing")
    fun scrollByCumulativeBeyondClampStopsIncreasing() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            ensureTallPage(driver, 20000.0) // raw height > 15000 triggers clamp
            var last = getScrollMetrics(driver).scrollY
            var iterations = 0
            var stopped = false
            while (iterations < 60 && !stopped) { // safety cap
                driver.scrollBy(800.0, smooth = false)
                val m = getScrollMetrics(driver)
                if (m.scrollY <= last + SMALL_TOL) {
                    // No significant increase -> reached clamp
                    stopped = true
                    val viewportH = m.viewportHeight
                    val expectedClampedMax = (15000.0 - viewportH).coerceAtLeast(0.0)
                    assertAlmostEquals(expectedClampedMax, m.scrollY, LARGE_TOL)
                } else {
                    last = m.scrollY
                }
                iterations++
            }
            assertTrue(stopped, "Did not reach clamp within iterations=$iterations last=$last")
        }
}
