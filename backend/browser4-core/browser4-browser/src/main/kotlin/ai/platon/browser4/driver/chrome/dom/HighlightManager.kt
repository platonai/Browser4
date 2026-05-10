package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.dom.model.InteractiveDOMTreeNodeList
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class HighlightManager(
    devTools: RemoteDevTools,
) {
    private val logger = getLogger(this)
    private val tracer get() = logger.takeIf { it.isTraceEnabled }
    private val cdp = CDP(devTools)

    suspend fun addHighlights(elements: InteractiveDOMTreeNodeList) {
        addHighlights0(elements)
    }

    suspend fun removeHighlights(elements: InteractiveDOMTreeNodeList) {
        removeHighlights0(elements)
    }

    suspend fun removeHighlights(force: Boolean = false) {
        if (!force) {
            removeHighlights0()
            return
        }

        var attempts = 5
        while (attempts-- > 0) {
            val eval = runCatching {
                cdp.evaluate("Boolean(document.querySelector('[data-b4-highlight]'))")
            }.getOrNull()
            val hasHighlights = eval?.result?.value == true
            if (!hasHighlights) break
            removeHighlights0()
            if (attempts > 0) delay(200.milliseconds)
        }
    }

    private suspend fun addHighlights0(elements: InteractiveDOMTreeNodeList) {
        try {
            val nodes = elements.nodes
            if (nodes.isEmpty()) return

            // Build minimal elements data needed by the JS highlighter
            // Prefer absoluteBounds; fallback to bounds
            val payload = StringBuilder()
            payload.append('[')
            var first = true
            for (n in nodes) {
                val rect = n.absoluteBounds ?: n.bounds
                val w = rect?.width ?: 0.0
                val h = rect?.height ?: 0.0
                if (w <= 0.0 || h <= 0.0) continue

                // Parse backendNodeId from locator: format "frameIndex,backendNodeId"
                val backendId = try {
                    val loc = n.backendNodeId
                    if (!loc.isNullOrBlank()) {
                        val parts = loc.split(',')
                        if (parts.size >= 2) parts[1].trim().toIntOrNull() else null
                    } else null
                } catch (_: Exception) {
                    null
                }

                val x = rect?.x ?: 0.0
                val y = rect?.y ?: 0.0

                if (!first) payload.append(',') else first = false
                payload.append("{")
                payload.append("\"x\":").append(x)
                payload.append(",\"y\":").append(y)
                payload.append(",\"width\":").append(w)
                payload.append(",\"height\":").append(h)
                if (backendId != null) payload.append(",\"backend_node_id\":").append(backendId)
                payload.append("}")
            }
            payload.append(']')

            if (payload.length <= 2) return // no valid elements

            if (logger.isDebugEnabled) {
                logger.debug("addHighlights | scrollAware | totalNodes=${nodes.size}")
            }

            val script = $$"""
(function() {
    try {
        const interactiveElements = $$payload;
        // Remove existing containers first to avoid duplicates
        const existing = document.getElementById('b4debug-highlights');
        if (existing) existing.remove();
        const stray = document.querySelectorAll('[data-b4-highlight]');
        stray.forEach(el => el.remove());

        const HIGHLIGHT_Z_INDEX = 2147483647;
        const container = document.createElement('div');
        container.id = 'b4debug-highlights';
        container.setAttribute('data-b4-highlight','container');
        container.style.cssText = `
            position: absolute;
            top: 0; left: 0;
            width: 100vw; height: 100vh;
            pointer-events: none;
            z-index: ${HIGHLIGHT_Z_INDEX};
            overflow: visible; margin: 0; padding: 0;
            border: none; outline: none; box-shadow: none; background: none;
            font-family: inherit;`;

        const scrollX = window.pageXOffset || document.documentElement.scrollLeft || 0;
        const scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
        if (console && console.debug) {
            console.debug('[b4] highlight debug | scrollX=', scrollX, 'scrollY=', scrollY, 'count=', interactiveElements.length);
        }

        let added = 0;
        interactiveElements.forEach((element, index) => {
            const w = Number(element.width), h = Number(element.height);
            if (!isFinite(w) || !isFinite(h) || w <= 0 || h <= 0) return;
            const x = Number(element.x) - scrollX;
            const y = Number(element.y) - scrollY;
            if (console && console.debug && index < 5) {
                console.debug('[b4] rect', index, 'abs=(', element.x, element.y, element.width, element.height, ') vp=(', x, y, element.width, element.height, ')');
            }

            const highlight = document.createElement('div');
            highlight.setAttribute('data-b4-highlight','element');
            if (element.backend_node_id != null)
                highlight.setAttribute('data-element-id', String(element.backend_node_id));
            highlight.style.cssText = `
                position: absolute;
                left: ${x}px; top: ${y}px;
                width: ${w}px; height: ${h}px;
                outline: 2px dashed #4a90e2; outline-offset: -2px;
                background: transparent; pointer-events: none;
                box-sizing: content-box; transition: outline 0.2s ease;
                margin: 0; padding: 0; border: none;`;

            const label = document.createElement('div');
            label.textContent = String(element.backend_node_id ?? (index + 1));
            label.style.cssText = `
                position: absolute; top: -20px; left: 0;
                background-color: #4a90e2; color: white; padding: 2px 6px;
                font-size: 11px; font-family: 'Monaco','Menlo','Ubuntu Mono',monospace;
                font-weight: bold; border-radius: 3px; white-space: nowrap;
                z-index: ${HIGHLIGHT_Z_INDEX + 1}; box-shadow: 0 2px 4px rgba(0,0,0,0.3);
                border: none; outline: none; margin: 0; line-height: 1.2;`;
            highlight.appendChild(label);

            container.appendChild(highlight);
            added++;
        });

        document.body.appendChild(container);
        return { added };
    } catch(e) {
        return { error: String(e && e.message || e) };
    }
})();
            """.trimIndent()

            runCatching {
                cdp.evaluate(script)
            }.onFailure { e ->
                logger.warn("Failed to add highlights | err={}", e.toString())
                tracer?.trace("addHighlights exception", e)
            }
        } catch (e: Exception) {
            logger.warn("addHighlights failed | err={}", e.toString())
            tracer?.trace("addHighlights outer exception", e)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun removeHighlights0(elements: InteractiveDOMTreeNodeList) {
        removeHighlights0()
    }

    private suspend fun removeHighlights0() {
        try {
            val script = """
(function() {
    try {
        let removed = 0;
        const highlights = document.querySelectorAll('[data-b4-highlight]');
        highlights.forEach(el => { el.remove(); removed++; });
        const container = document.getElementById('b4debug-highlights');
        if (container) { container.remove(); removed++; }
        const orphaned = document.querySelectorAll('[data-b4-highlight="tooltip"]');
        orphaned.forEach(el => { el.remove(); removed++; });
        return { removed };
    } catch(e) {
        return { error: String(e && e.message || e) };
    }
})();
            """.trimIndent()

            runCatching {
                cdp.evaluate(script)
            }.onFailure { e ->
                logger.warn("Failed to remove highlights | err={}", e.toString())
                tracer?.trace("removeHighlights exception", e)
            }
        } catch (e: Exception) {
            logger.warn("removeHighlights failed | err={}", e.toString())
            tracer?.trace("removeHighlights outer exception", e)
        }
    }
}
