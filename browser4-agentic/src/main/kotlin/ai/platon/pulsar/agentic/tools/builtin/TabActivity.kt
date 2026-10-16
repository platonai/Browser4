package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver

/**
 * Which of a browser's listed tabs is the active one, and in which order the tabs
 * are numbered.
 *
 * The active flag was computed independently in every tab-listing call site
 * (`browser.listTabs`, the REST tabs endpoint, the pages endpoint) and each got
 * the null case wrong in its own way. The rule lives here so a listing can never
 * disagree with another listing about the same browser:
 *
 * - the browser's `frontDriver` when it is one of the listed tabs — that is the
 *   tab `switchTab`/`bringToFront` last activated;
 * - otherwise the first listed tab.
 *
 * The fallback is what makes the flag usable at all for a freshly opened managed
 * session: `frontDriver` is only assigned by `bringToFront()`, so before any tab
 * switch it is `null` and every tab compared unequal, and the CLI's
 * `.pages[] | select(.active)` silently selected nothing. A `frontDriver` from
 * another browser (or from a tab that has since closed) is deliberately NOT
 * honoured: reporting the first tab is a better answer than reporting none.
 */
object TabActivity {

    /**
     * The browser's tabs in a **stable creation order**.
     *
     * `AbstractBrowser.listDrivers()` returns `ConcurrentHashMap.values.toList()`
     * — hash order, which is neither insertion order nor Chrome's tab-strip order,
     * and can differ between two calls over the same set (a rehash, or
     * `recoverUnmanagedPages()` adding a driver). Numbering tabs from that list
     * made `tab-list` and a following `tab-select <index>` disagree, and made an
     * index address a different tab after any unrelated add/remove.
     *
     * `AbstractWebDriver.id` is a monotonically increasing creation counter, so
     * ordering by it is deterministic and reproducible across calls, and stable
     * under change: a tab added later sorts last, and removing a tab leaves the
     * survivors' relative order untouched.
     *
     * Only the ORDER is defined here — which tabs are listed stays whatever the
     * browser reports.
     *
     * @param browser the browser whose tabs are being numbered; the plain
     *   [Browser] interface is enough, so callers holding a `boundBrowser`
     *   reference (typed as `Browser?`) need no cast.
     */
    suspend fun orderedTabDrivers(browser: Browser): List<AbstractWebDriver> =
        browser.listDrivers()
            .filterIsInstance<AbstractWebDriver>()
            .sortedBy { it.id }

    /**
     * The guid of the tab [drivers] should report as active, or `null` when there
     * is no tab to report.
     *
     * @param drivers the tabs in listing order (see [orderedTabDrivers])
     * @param frontDriver the browser's front driver, when it has one
     */
    fun resolveActiveGuid(drivers: List<AbstractWebDriver>, frontDriver: WebDriver?): String? {
        val frontGuid = (frontDriver as? AbstractWebDriver)?.guid
        return frontGuid?.takeIf { guid -> drivers.any { it.guid == guid } }
            ?: drivers.firstOrNull()?.guid
    }
}
