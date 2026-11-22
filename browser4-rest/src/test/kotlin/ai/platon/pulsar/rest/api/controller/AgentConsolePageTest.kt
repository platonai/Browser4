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
            "submitBtn",      // start
            "steerInput",     // steer a running task
            "cancelBtn",      // stop
            "steps",          // live step log
            "resultCard",     // the payoff
        ).forEach { id ->
            assertTrue(html.contains("id=\"$id\""), "missing control #$id")
        }
        assertTrue(html.contains("agent run -i"), "the page explains what it mirrors")
        assertTrue(html.contains("开始任务"), "the primary action keeps its plain-language label")
    }
}
