package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Requirement 1.1's drift gate: the tab domain's advertised tool set is the
 * **scanned** set (the `WebDriver.kt` mirror) plus a handful of deliberate
 * declarations, and the difference must be exactly the documented one.
 *
 * Why this exists: the scanner mirrors an interface by *text*, and two failure modes
 * have already bitten this repository —
 *
 * - a method silently disappearing from the advertised surface (an overload swap in
 *   `WebDriver.kt` changed `tab.navigate`'s signature once, and `isEnabled` /
 *   `dialogStatus` were dispatchable for months without being advertised at all);
 * - an explicit declaration being added without anybody noticing that the generated
 *   and advertised sets diverged.
 *
 * Both show up here as a diff against a list somebody had to write down on purpose.
 * The two sets are not supposed to be equal: publishing a method the mirrored
 * interface does not declare is a deliberate act (and the reverse — a mirrored method
 * we deliberately do not publish — is listed too).
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("tab spec coverage — scanned vs advertised")
class TabSpecCoverageTest {

    /**
     * Advertised although the mirrored `WebDriver` interface does not declare them.
     *
     * Together these are the whole hand-written part of the tab surface: everything
     * else in the domain is generated from the mirror, so a method appearing here is a
     * statement that Browser4 deliberately publishes more than the base library.
     */
    private val declaredByHand = setOf(
        // Aliases/overloads the executor implements on top of the mirror: `eval` is the
        // CLI's shorthand for evaluateValue, and `type` adds the auto/chars/exec modes.
        "eval",
        "type",
        // Dispatched by the executor and reachable through frontend aliases, but absent
        // from the mirror (see BrowserTabToolExecutor's declaration comment).
        "isEnabled",
        "dialogStatus",
        // Console capture: a Browser4 extension — the mirror has no console API.
        "consoleMessages",
        "consoleClear",
        // Network observation and HAR recording: also Browser4 extensions.
        "networkRequests",
        "networkRequestDetail",
        "harStart",
        "harStop",
        "networkRoute",
        "networkUnroute",
    )

    /** Mirrored methods that are deliberately not advertised as tools. */
    private val notAdvertised = emptySet<String>()

    @Test
    @DisplayName("the advertised tab set is the scanned set plus the documented additions")
    fun advertisedEqualsScannedPlusDeclared() {
        ToolSpecGenerator.generateAllOnce()
        val scanned = ToolSpecGenerator.webDriverToolSpecs.map { it.method }.toSet()
        val advertised = BrowserTabToolExecutor().getToolSpecs().keys

        assertEquals(
            emptySet<String>(), advertised - scanned - declaredByHand,
            "these tools are advertised without being scanned from the mirror or listed " +
                "in `declaredByHand` — add them there (with a reason) once you are sure they " +
                "are deliberate",
        )
        assertEquals(
            emptySet<String>(), scanned - advertised - notAdvertised,
            "these mirrored methods are no longer advertised — either the scanner dropped " +
                "them (a real regression) or the removal is deliberate and belongs in " +
                "`notAdvertised` with a reason",
        )
    }

    @Test
    @DisplayName("the documented additions are still real tools")
    fun declaredAdditionsStillExist() {
        val advertised = BrowserTabToolExecutor().getToolSpecs().keys
        assertEquals(
            emptySet<String>(), declaredByHand - advertised,
            "a method listed in `declaredByHand` is gone; remove it from the list so the " +
                "documented boundary stays true",
        )
    }
}
