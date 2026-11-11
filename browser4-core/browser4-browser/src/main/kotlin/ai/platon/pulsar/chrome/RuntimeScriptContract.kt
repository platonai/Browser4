package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.getLogger

/**
 * Guards the three injected scripts that jointly define the `vi` coordinate model.
 *
 * `vi` (and therefore every `interactiveElements[].box`, the X-SQL `box=` output
 * and the inspect layout buckets) is only document-absolute when all three
 * scripts come from the same base-library generation:
 *
 * - `js/dom_rect.js` provides `getDocumentRect` and no longer subtracts a cached
 *   `bodyRect` (`browser4-browser` vendors this file, so it can silently come
 *   from a different generation than the next two);
 * - `js/node_ops.js` calls `getDocumentRect` for `NodeOps.getRect`;
 * - `js/__pulsar_utils__.js` computes the data with the memory `_computeDone`
 *   flag (no `body[data-error]` guard, no forced scroll-to-top).
 *
 * A half-wired bundle does not fail on its own: the old pair simply reports
 * viewport coordinates (or body-relative ones) that look perfectly valid, and
 * every downstream consumer silently misplaces the pointer by `scrollY`. This
 * check turns that state into an explicit, actionable failure at driver
 * creation, before any coordinate is produced.
 *
 * Consistent **old** scripts are rejected too: a runtime whose scripts predate
 * the document model means a stale bundle is being used from a source tree that
 * expects the new contract, which is exactly the "rebuilt the source but ran an
 * old bundle" trap (see `docs-dev/copilot/20261109-mouse-pointer-issues-and-pitfalls.md` §12.5–§12.7).
 */
object RuntimeScriptContract {

    private val logger = getLogger(this)

    internal const val DOM_RECT_RESOURCE = "js/dom_rect.js"
    internal const val NODE_OPS_RESOURCE = "js/node_ops.js"
    internal const val UTILS_RESOURCE = "js/__pulsar_utils__.js"

    /** Present only in the document-coordinate generation. */
    internal const val DOCUMENT_MARKER = "getDocumentRect"

    /** Present only in the legacy, body-relative `dom_rect.js`. */
    internal const val LEGACY_DOM_RECT_MARKER = "bodyRect"

    /** The legacy `node_ops.js` measured with the viewport-only helper. */
    internal const val LEGACY_NODE_OPS_MARKER = "getClientRect"

    /** Present only in the generation that recomputes stale `vi` data. */
    internal const val LAYOUT_FRESHNESS_MARKER = "_computeDone"

    @Volatile
    private var verified = false

    /**
     * Verify the effective classpath resources once per JVM.
     *
     * @throws IllegalStateException when the three scripts disagree or predate the
     *   document-coordinate model — the message names the mismatched file and the fix.
     */
    fun verify() {
        if (verified) {
            return
        }
        synchronized(this) {
            if (verified) {
                return
            }
            val problems = validate(
                read(DOM_RECT_RESOURCE),
                read(NODE_OPS_RESOURCE),
                read(UTILS_RESOURCE)
            )
            if (problems.isNotEmpty()) {
                throw IllegalStateException(
                    "The injected runtime scripts disagree on the `vi` coordinate model:\n" +
                        problems.joinToString("\n") { "  - $it" } + "\n" +
                        "Fix: align the runtime with the source tree — rebuild the bundle " +
                        "(`browser4-cli doctor --rebuild` or a CLI run with `--force-rebuild-bundle`) " +
                        "and make sure `browser4-base.version` matches the vendored `js/dom_rect.js`."
                )
            }
            verified = true
            logger.info("Runtime scripts agree on the document coordinate model")
        }
    }

    /**
     * The mismatches in one set of script bodies, each a message naming the
     * offending resource; empty when they agree.
     *
     * Pure and side-effect free so the failure modes can be tested without a
     * mixed classpath.
     */
    internal fun validate(domRect: String, nodeOps: String, utils: String): List<String> {
        val problems = mutableListOf<String>()

        if (domRect.isBlank()) {
            problems += "$DOM_RECT_RESOURCE is missing from the runtime classpath"
        }
        if (nodeOps.isBlank()) {
            problems += "$NODE_OPS_RESOURCE is missing from the runtime classpath"
        }
        if (utils.isBlank()) {
            problems += "$UTILS_RESOURCE is missing from the runtime classpath"
        }
        if (problems.isNotEmpty()) {
            return problems
        }

        val domRectDocumentSpace = domRect.contains(DOCUMENT_MARKER)
        if (!domRectDocumentSpace && !domRect.contains(LEGACY_DOM_RECT_MARKER)) {
            return listOf(
                "$DOM_RECT_RESOURCE declares neither $DOCUMENT_MARKER nor $LEGACY_DOM_RECT_MARKER — " +
                    "it is not a dom_rect.js this runtime understands"
            )
        }

        val nodeOpsDocumentSpace = nodeOps.contains(DOCUMENT_MARKER)
        val nodeOpsLegacy = nodeOps.contains(LEGACY_NODE_OPS_MARKER)
        if (nodeOpsDocumentSpace == nodeOpsLegacy) {
            problems += "$NODE_OPS_RESOURCE is neither the document-space nor the legacy " +
                "(${LEGACY_NODE_OPS_MARKER}) generation"
        } else if (domRectDocumentSpace != nodeOpsDocumentSpace) {
            problems += if (domRectDocumentSpace) {
                "$DOM_RECT_RESOURCE is the document-space generation but $NODE_OPS_RESOURCE still " +
                    "calls ${LEGACY_NODE_OPS_MARKER}: `vi` would silently be viewport coordinates"
            } else {
                "$NODE_OPS_RESOURCE expects $DOCUMENT_MARKER but $DOM_RECT_RESOURCE is the legacy " +
                    "(body-relative) generation: every `vi` would throw or fall back"
            }
        }

        val layoutFresh = utils.contains(LAYOUT_FRESHNESS_MARKER)
        if (domRectDocumentSpace != layoutFresh) {
            problems += if (domRectDocumentSpace) {
                "$DOM_RECT_RESOURCE is the document-space generation but $UTILS_RESOURCE still " +
                    "computes `vi` once per document (no $LAYOUT_FRESHNESS_MARKER and a forced " +
                    "scroll-to-top): a stale bundle is being used from a new source tree"
            } else {
                "$UTILS_RESOURCE already tracks layout freshness but $DOM_RECT_RESOURCE is the " +
                    "legacy (body-relative) generation"
            }
        }

        if (!domRectDocumentSpace && !layoutFresh) {
            problems += "the runtime bundle predates the document-coordinate model (legacy " +
                "dom_rect/node_ops/__pulsar_utils__); rebuild it instead of running mixed or stale scripts"
        }

        return problems
    }

    private fun read(resource: String): String = runCatching {
        ResourceLoader.readAllLinesNoFilter(resource).joinToString("\n")
    }.onFailure {
        logger.warn("Failed to read the runtime script {} | {}", resource, it.message)
    }.getOrDefault("")
}
