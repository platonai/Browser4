package ai.platon.pulsar.rest.api.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests for the static agent console page (`static/agent.html`).
 *
 * The page is plain HTML + JavaScript with no build step and no external assets,
 * so these tests guard the two things that would silently break it: the page
 * being dropped from the packaged resources, and it calling an endpoint that no
 * longer exists. They read the resource as Spring Boot serves it, not the source
 * tree, so packaging is covered too.
 */
class AgentConsolePageTest {

    private val html: String by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/static/agent.html")) {
            "agent.html must be packaged under src/main/resources/static"
        }
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    @DisplayName("the console page is a single self-contained document")
    fun consolePageIsSelfContained() {
        assertTrue(html.contains("<!DOCTYPE html>"), "must be a standalone document")
        assertTrue(html.contains("lang=\"zh-CN\""), "the page speaks to terminal users in Chinese")

        assertFalse(
            Regex("""<script[^>]+src\s*=""").containsMatchIn(html),
            "no external script: the page must work offline and without a CDN"
        )
        assertFalse(
            Regex("""<link[^>]+href\s*=""").containsMatchIn(html),
            "no external stylesheet or icon"
        )
        assertFalse(html.contains("@import"), "no imported CSS")
        assertFalse(
            Regex("""url\(\s*['"]?https?://""").containsMatchIn(html),
            "no remote backgrounds/fonts"
        )
    }

    @Test
    @DisplayName("the console page drives the documented command endpoints")
    fun consolePageUsesTheCommandEndpoints() {
        listOf(
            "/api/commands/plain?async=true&sessionId=",
            "/events/stream",
            "/events?after=",
            "/input",
            "/cancel",
            "/result?sessionId=",
            "/stats?sessionId=",
            "/status?sessionId=",
        ).forEach { endpoint ->
            assertTrue(html.contains(endpoint), "agent.html no longer calls $endpoint")
        }
    }

    @Test
    @DisplayName("the console page exposes the controls a terminal user needs")
    fun consolePageExposesTheCoreControls() {
        listOf(
            "taskInput",      // the task text
            "submitBtn",      // start / send
            "followupToggle", // keep it for the next stop instead of mid-run
            "cancelBtn",      // stop
            "steps",          // live step log
            "runPanel",       // the collapsible execution panel
            "runToggle",      // collapse / expand it
            "resultCard",     // the payoff
            "tipText",        // the rotating hint strip
        ).forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing control #$id")
        }
        assertTrue(html.contains("agent run -i"), "the page explains what it mirrors")
        assertTrue(html.contains("开始任务"), "the primary action keeps its plain-language label")
    }

    @Test
    @DisplayName("the console page has one composer: no separate steer box, no fixed tutorial steps")
    fun consolePageHasASingleComposer() {
        assertFalse(html.contains("id=\"steerInput\""), "steering must reuse the one composer input")
        assertFalse(html.contains("id=\"steerBox\""), "there must be no second input box")
        assertFalse(html.contains("第 1 步"), "fixed onboarding steps were replaced by the rotating tips")
        assertFalse(html.contains("遇到问题"), "the side help panel is gone")
    }

    @Test
    @DisplayName("the sidebar exposes workspaces, their sessions, and both collapse switches")
    fun consolePageHasTheSessionSidebar() {
        listOf(
            "sidebar",           // the panel itself
            "sessionTree",       // workspace -> session tree
            "sidebarToggle",     // collapse / expand the whole panel
            "sidebarCollapse",   // the in-panel variant of the same action
            "sidebarRefresh",    // re-read the tree
            "sidebarNewSession", // start a new session from the sidebar
            "sidebarEmpty",      // the empty state
        ).forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing sidebar control #$id")
        }
        // The panel is fed by the session index endpoints, not by re-deriving tasks client-side.
        assertTrue(html.contains("/api/agent/sessions"), "the sidebar no longer calls the session index")
        assertTrue(html.contains("/context"), "a clicked session restores its context")
        // The two labels an operator must not have to guess.
        assertTrue(html.contains("工作区"), "the sidebar names the workspace grouping")
        assertTrue(html.contains("临时工作区"), "a session without --workspace is a temporary workspace")
    }

    @Test
    @DisplayName("the page is a left-right shell: fixed session column, composer in the content column")
    fun consolePageUsesALeftRightShell() {
        // The sidebar is a body-level column (before the content) and the layout collapses by
        // one class on <body>; both are what make the two columns independent.
        val body = html.substringAfter("<body>")
        assertTrue(
            body.indexOf("id=\"sidebar\"") in 0 until body.indexOf("class=\"wrap\""),
            "the sidebar must be a body-level sibling of the content column, not nested in it",
        )
        assertTrue(html.contains("--sidebar-w"), "the content column and composer offset by the sidebar width")
        assertTrue(html.contains("body.sidebar-collapsed"), "one class collapses the whole layout")
        assertTrue(
            html.contains("left: var(--sidebar-w)"),
            "the docked composer belongs to the content column, not the whole page",
        )
        // The composer lives inside <main> so the idle mode can center it with the headline.
        val main = html.indexOf("<main class=\"main\">")
        assertTrue(main in 0 until html.indexOf("id=\"composerWrap\""), "the composer must be inside the main column")
        assertTrue(html.indexOf("id=\"composerWrap\"") < html.indexOf("</main>"), "the composer stays in <main>")
    }

    @Test
    @DisplayName("the composer is centered while idle and docks to the bottom once a task runs")
    fun consolePageCentersThenDocksTheComposer() {
        assertTrue(html.contains("id=\"idleHero\""), "the idle page needs its headline above the composer")
        assertTrue(html.contains("composer-docked"), "one body class switches the composer mode")
        assertTrue(
            html.contains("body:not(.composer-docked) .main"),
            "idle centering is expressed in CSS, not by moving nodes",
        )
        assertTrue(
            html.contains("body.composer-docked .composer-wrap"),
            "the docked position is the override of the idle shape",
        )
        // The mode is driven by the two moments that matter: a started task and a restored session.
        assertTrue(html.contains("setComposerDocked(true)"), "starting a task must dock the composer")
        assertTrue(html.contains("setComposerDocked(false)"), "a brand-new session returns to the idle shape")
    }

    @Test
    @DisplayName("the composer's dialog picks the workspace the next tasks run in")
    fun consolePageHasTheWorkspacePicker() {
        listOf(
            "workspaceSelect",       // the dropdown in the composer's control row
            "refreshWorkspacesBtn",  // re-read the workspace list
            "composerSession",       // names the session the next task joins
        ).forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing workspace control #$id")
        }
        assertTrue(
            html.indexOf("id=\"composerWrap\"") < html.indexOf("id=\"workspaceSelect\""),
            "the workspace picker belongs to the composer, not to the settings dialog",
        )
        assertTrue(html.contains("b4.agent.web.workspace"), "the choice is remembered across visits")
        assertTrue(html.contains("&workspace="), "the submission carries the chosen workspace")
    }

    @Test
    @DisplayName("the sidebar remembers its own layout without a server round-trip")
    fun consolePagePersistsSidebarState() {
        assertTrue(html.contains("b4.agent.web.sidebar"), "sidebar prefs need their own storage key")
        assertTrue(html.contains("sidebarGroups"), "folded workspaces are remembered per group")
        assertTrue(html.contains("localStorage"), "layout prefs are client-side")
    }
}
