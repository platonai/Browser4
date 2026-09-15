package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec

/**
 * Callable examples for the tab domain, kept beside [BrowserTabToolExecutor] rather
 * than inside it (requirement 2).
 *
 * The executor's specs are generated from the mirrored `WebDriver` source, and the
 * examples those carry are KDoc snippets — documentation, not calls a client can
 * send. These are real argument maps, which is what makes them usable as contract
 * test inputs (`ToolContractMatrixTest.happyPathHoldsForEveryTool` validates every
 * example against the signature it documents) and what keeps the published
 * reference honest.
 *
 * A tool with no arguments is marked [ToolExample.runnable]: an empty argument list
 * is a valid call, but `args = emptyMap()` is indistinguishable from "no example was
 * written".
 */
internal object TabToolExamples {

    /** `tab` method → the examples a client should start from. */
    val EXECUTABLE: Map<String, List<ToolExample>> = mapOf(
        "navigate" to listOf(
            ToolExample(title = "Open a page", args = mapOf("url" to "https://example.com")),
        ),
        "open" to listOf(
            ToolExample(title = "Open a page in a new tab", args = mapOf("url" to "https://example.com")),
        ),
        "click" to listOf(
            ToolExample(title = "Click a button", args = mapOf("selector" to "#submit")),
            ToolExample(title = "Click a row twice", args = mapOf("selector" to "#row-1", "count" to "2")),
        ),
        "dblclick" to listOf(
            ToolExample(title = "Double click a cell", args = mapOf("selector" to "#cell-1")),
        ),
        "fill" to listOf(
            ToolExample(title = "Fill a search box", args = mapOf("selector" to "#q", "text" to "browser4")),
        ),
        "type" to listOf(
            ToolExample(title = "Type into a field", args = mapOf("text" to "hello", "selector" to "#comment")),
        ),
        "press" to listOf(
            ToolExample(title = "Submit with Enter", args = mapOf("key" to "Enter", "selector" to "#q")),
        ),
        "hover" to listOf(ToolExample(title = "Hover a menu", args = mapOf("selector" to "#menu"))),
        "focus" to listOf(ToolExample(title = "Focus an input", args = mapOf("selector" to "#email"))),
        "check" to listOf(ToolExample(title = "Tick a checkbox", args = mapOf("selector" to "#terms"))),
        "uncheck" to listOf(ToolExample(title = "Clear a checkbox", args = mapOf("selector" to "#newsletter"))),
        "selectOption" to listOf(
            ToolExample(title = "Pick a country", args = mapOf("selector" to "#country", "value" to "CN")),
        ),
        // `getText`/`getAttribute` used to be listed here, but no spec and no alias
        // ever advertised them — the equivalent reads are `selectFirstTextOrNull`
        // and `selectFirstAttributeOrNull`. Writing examples for tools a client
        // cannot call is documentation that lies, so they are gone.
        "exists" to listOf(ToolExample(title = "Check an element exists", args = mapOf("selector" to "#cart"))),
        "isVisible" to listOf(
            ToolExample(title = "Check an element is visible", args = mapOf("selector" to "#banner")),
        ),
        "waitForSelector" to listOf(
            ToolExample(
                title = "Wait for results to render",
                args = mapOf("selector" to "#results", "timeoutMillis" to "5000"),
            ),
        ),
        "evaluateValue" to listOf(
            ToolExample(title = "Read the page title in JS", args = mapOf("expression" to "document.title")),
        ),
        "eval" to listOf(
            ToolExample(title = "Evaluate an expression", args = mapOf("expression" to "document.title")),
        ),
        "screenshot" to listOf(
            ToolExample(title = "Capture the full page", args = mapOf("fullPage" to "true")),
            ToolExample(title = "Capture one element", args = mapOf("selector" to "#chart")),
        ),
        "ariaSnapshot" to listOf(
            ToolExample(title = "Compact accessibility snapshot", args = mapOf("compact" to "true")),
        ),
        "scrollTo" to listOf(
            ToolExample(title = "Scroll an element into view", args = mapOf("selector" to "#footer")),
        ),
        "waitForNavigation" to listOf(
            ToolExample(
                title = "Wait for the page after leaving a URL",
                args = mapOf("oldUrl" to "https://example.com/login", "timeoutMillis" to "10000"),
            ),
        ),
        "frameSwitch" to listOf(ToolExample(title = "Enter an iframe", args = mapOf("frame" to "#pay-frame"))),
        "upload" to listOf(
            ToolExample(title = "Attach a file", args = mapOf("selector" to "#file", "paths" to "/tmp/a.pdf")),
        ),
        "delay" to listOf(ToolExample(title = "Wait half a second", args = mapOf("millis" to "500"))),
        "resize" to listOf(
            ToolExample(title = "Resize the viewport", args = mapOf("width" to "1280", "height" to "800")),
        ),
        "scrollBy" to listOf(
            ToolExample(title = "Scroll down one viewport", args = mapOf("pixels" to "800")),
        ),
        "scrollToViewport" to listOf(
            ToolExample(title = "Jump to the third viewport", args = mapOf("n" to "3")),
        ),
        "mouseWheel" to listOf(
            ToolExample(title = "Wheel inside an element", args = mapOf("selector" to "#list", "deltaY" to "300")),
        ),
        "mouseMove" to listOf(
            ToolExample(title = "Move the pointer", args = mapOf("x" to "320", "y" to "180")),
        ),
        "mouseDown" to listOf(ToolExample(title = "Press and hold the left button", args = mapOf("button" to "left"))),
        "mouseUp" to listOf(ToolExample(title = "Release the left button", args = mapOf("button" to "left"))),
        "drag" to listOf(
            ToolExample(
                title = "Drag one element onto another",
                args = mapOf("sourceSelector" to "#card-1", "targetSelector" to "#done"),
            ),
        ),
        "textContent" to listOf(
            ToolExample(title = "Read the text content of a node", args = mapOf("selector" to "#price")),
        ),
        "outerHTML" to listOf(
            ToolExample(title = "Read the outer HTML of a node", args = mapOf("selector" to "#price")),
        ),
        "boundingBox" to listOf(
            ToolExample(title = "Measure an element", args = mapOf("selector" to "#hero")),
        ),
        "clickablePoint" to listOf(
            ToolExample(title = "Find the clickable point", args = mapOf("selector" to "#submit")),
        ),
        "generateLocator" to listOf(
            ToolExample(title = "Build a stable locator", args = mapOf("selector" to "#submit")),
        ),
        "waitForPage" to listOf(
            ToolExample(
                title = "Wait for a URL to load",
                args = mapOf("url" to "https://example.com/next", "timeoutMillis" to "15000"),
            ),
        ),
        "waitForFunction" to listOf(
            ToolExample(
                title = "Wait for a client-side condition",
                args = mapOf("pageFunction" to "document.querySelectorAll('.row').length > 10"),
            ),
        ),
        "loadStorageState" to listOf(
            ToolExample(
                title = "Restore a saved session",
                args = mapOf("state" to """{"cookies":[],"origins":[]}"""),
            ),
        ),
        "setAttribute" to listOf(
            ToolExample(
                title = "Set an attribute before clicking",
                args = mapOf("selector" to "#q", "attrName" to "data-test", "attrValue" to "1"),
            ),
        ),
        "clickTextMatches" to listOf(
            ToolExample(
                title = "Click the first row whose text matches",
                args = mapOf("selector" to "button", "pattern" to "Buy now"),
            ),
        ),
        "executeCdpCommand" to listOf(
            ToolExample(title = "Ask Chrome for the document root", args = mapOf("method" to "DOM.getDocument")),
        ),
        "harStart" to listOf(
            ToolExample(title = "Start recording network traffic", args = mapOf("contentMode" to "embed")),
        ),
        "networkRoute" to listOf(
            ToolExample(
                title = "Stub an API response",
                args = mapOf("urlPattern" to "**/api/ads", "body" to "{}", "contentType" to "application/json"),
            ),
        ),
        "networkUnroute" to listOf(
            ToolExample(title = "Remove an API stub", args = mapOf("urlPattern" to "**/api/ads")),
        ),
        "isEnabled" to listOf(
            ToolExample(title = "Is the submit button enabled?", args = mapOf("selector" to "#submit")),
        ),
        // No-argument reads and utilities: the empty call *is* the example.
        "title" to listOf(ToolExample(title = "Page title", runnable = true)),
        "currentUrl" to listOf(ToolExample(title = "Current URL", runnable = true)),
        "reload" to listOf(ToolExample(title = "Reload the page", runnable = true)),
        "goBack" to listOf(ToolExample(title = "Go back", runnable = true)),
        "goForward" to listOf(ToolExample(title = "Go forward", runnable = true)),
        "scrollToTop" to listOf(ToolExample(title = "Scroll to the top", runnable = true)),
        "scrollToBottom" to listOf(ToolExample(title = "Scroll to the bottom", runnable = true)),
        "consoleMessages" to listOf(ToolExample(title = "Read console output", runnable = true)),
        "saveStorageState" to listOf(ToolExample(title = "Save cookies and localStorage", runnable = true)),
        "frameList" to listOf(ToolExample(title = "List frames", runnable = true)),
        "dialogStatus" to listOf(ToolExample(title = "Check for a pending dialog", runnable = true)),
        "networkRequests" to listOf(ToolExample(title = "List network requests", runnable = true)),
        "pageSource" to listOf(ToolExample(title = "Read the page HTML", runnable = true)),
        "referrer" to listOf(ToolExample(title = "Read the referrer", runnable = true)),
        "documentURI" to listOf(ToolExample(title = "Read the document URI", runnable = true)),
        "nanoDOMTree" to listOf(ToolExample(title = "Dump the DOM as YAML", runnable = true)),
        "getCookies" to listOf(ToolExample(title = "List cookies", runnable = true)),
        "clearBrowserCookies" to listOf(ToolExample(title = "Clear cookies", runnable = true)),
        "bringToFront" to listOf(ToolExample(title = "Focus the tab", runnable = true)),
        "pdf" to listOf(ToolExample(title = "Print the page to PDF", runnable = true)),
        "frameMain" to listOf(ToolExample(title = "Return to the main frame", runnable = true)),
        "dialogDismiss" to listOf(ToolExample(title = "Dismiss a pending dialog", runnable = true)),
        "dialogAccept" to listOf(ToolExample(title = "Accept a pending dialog", runnable = true)),
        "consoleClear" to listOf(ToolExample(title = "Clear the console buffer", runnable = true)),
        "pause" to listOf(ToolExample(title = "Pause the driver", runnable = true)),
        "stop" to listOf(ToolExample(title = "Stop the driver", runnable = true)),
        "harStop" to listOf(ToolExample(title = "Stop HAR recording", runnable = true)),
    )
}

/**
 * Replace the examples of the specs named in [examples], leaving unknown methods and
 * every other field untouched.
 */
internal fun MutableMap<String, ToolSpec>.replaceExamples(examples: Map<String, List<ToolExample>>) {
    examples.forEach { (method, list) ->
        this[method]?.let { existing -> this[method] = existing.copy(examples = list) }
    }
}
