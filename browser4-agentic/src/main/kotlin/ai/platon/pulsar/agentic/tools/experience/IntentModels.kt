package ai.platon.pulsar.agentic.tools.experience

/**
 * The agent's real goal — what the user actually wants, not what MCP tools are called.
 *
 * Two identical action sequences (Search → Enter) map to different Intents
 * (SEARCH_PRODUCT vs SEARCH_ARTICLE) and therefore different KnowledgeFacts.
 *
 * Each Intent carries its expected canonical action sequence, used by
 * [IntentClassifier] to match free-text intent descriptions.
 */
enum class Intent(
    val displayName: String,
    val canonicalActions: List<String>,
) {
    BUY(
        "Buy",
        listOf("search", "select", "add_to_cart", "checkout"),
    ),
    SEARCH(
        "Search",
        listOf("navigate", "type", "submit", "extract"),
    ),
    BOOK(
        "Book",
        listOf("search", "select", "fill_form", "confirm"),
    ),
    LOGIN(
        "Login",
        listOf("navigate", "fill", "submit"),
    ),
    CHECKOUT(
        "Checkout",
        listOf("review", "fill", "confirm"),
    ),
    EXTRACT(
        "Extract",
        listOf("navigate", "extract"),
    ),
    COMPARE(
        "Compare",
        listOf("search", "extract", "compare"),
    ),
    DOWNLOAD(
        "Download",
        listOf("navigate", "click", "wait"),
    ),
    READ(
        "Read",
        listOf("navigate", "scroll", "extract"),
    ),
    FILL_FORM(
        "Fill Form",
        listOf("navigate", "fill", "submit"),
    ),
    MONITOR(
        "Monitor",
        listOf("navigate", "check", "compare"),
    ),
    OTHER(
        "Other",
        listOf(),
    );

    companion object {
        /**
         * Classify a free-text intent description to the closest [Intent].
         *
         * Simple keyword match for Phase 1; LLM-based classification in Phase 3+.
         */
        fun classify(intentText: String?): Intent {
            if (intentText.isNullOrBlank()) return OTHER

            val lower = intentText.lowercase().trim()

            // Keyword scoring: each keyword match adds to the intent's score
            val scores = mutableMapOf<Intent, Int>()

            for (intent in entries) {
                var score = 0
                for (action in intent.canonicalActions) {
                    if (action in lower) score += 2
                }
                // Display name match
                if (intent.displayName.lowercase() in lower) score += 3
                scores[intent] = score
            }

            // Buy-specific keywords
            if (anyWordIn(lower, "buy", "purchase", "order", "add to cart", "cheapest", "best price")) {
                scores[BUY] = (scores[BUY] ?: 0) + 4
            }
            // Search-specific keywords
            if (anyWordIn(lower, "search", "find", "lookup", "query")) {
                scores[SEARCH] = (scores[SEARCH] ?: 0) + 4
            }
            // Book-specific keywords
            if (anyWordIn(lower, "book", "reserve", "appointment", "ticket", "flight", "hotel")) {
                scores[BOOK] = (scores[BOOK] ?: 0) + 4
            }
            // Login-specific keywords
            if (anyWordIn(lower, "login", "sign in", "authenticate", "log in")) {
                scores[LOGIN] = (scores[LOGIN] ?: 0) + 4
            }
            // Checkout-specific keywords
            if (anyWordIn(lower, "checkout", "check out", "place order", "confirm purchase")) {
                scores[CHECKOUT] = (scores[CHECKOUT] ?: 0) + 4
            }
            // Extract-specific keywords
            if (anyWordIn(lower, "extract", "scrape", "get data", "fetch", "collect", "gather")) {
                scores[EXTRACT] = (scores[EXTRACT] ?: 0) + 4
            }
            // Compare-specific keywords
            if (anyWordIn(lower, "compare", "vs", "versus", "difference between")) {
                scores[COMPARE] = (scores[COMPARE] ?: 0) + 4
            }
            // Download-specific keywords
            if (anyWordIn(lower, "download", "save file", "export", "get file")) {
                scores[DOWNLOAD] = (scores[DOWNLOAD] ?: 0) + 4
            }
            // Read-specific keywords
            if (anyWordIn(lower, "read", "article", "news", "blog", "post", "story")) {
                scores[READ] = (scores[READ] ?: 0) + 4
            }
            // Fill-form-specific keywords
            if (anyWordIn(lower, "fill", "form", "register", "sign up", "subscribe", "apply")) {
                scores[FILL_FORM] = (scores[FILL_FORM] ?: 0) + 3
            }
            // Monitor-specific keywords
            if (anyWordIn(lower, "monitor", "watch", "track", "alert", "notify", "check if")) {
                scores[MONITOR] = (scores[MONITOR] ?: 0) + 4
            }

            val best = scores.maxByOrNull { it.value }
            return if (best != null && best.value > 0) best.key else OTHER
        }

        private fun anyWordIn(text: String, vararg words: String): Boolean {
            return words.any { it in text }
        }
    }
}

// =============================================================================
// Failure Taxonomy
// =============================================================================

/**
 * Structured failure classification for the learning system.
 *
 * Each category carries a recoverability flag and a suggested recovery action.
 * Failures are classified from [errorMessage] and trace context by [FailureClassifier].
 *
 * These categories drive:
 * - ExperienceStats.failureStats (category → count)
 * - KnowledgeFacts.knownBlockers (new blocker types discovered)
 * - KnowledgeFacts.antiPatterns (selectors that consistently fail)
 * - Retrieval tier degradation (anti_bot failures force P3 even with high confidence)
 */
enum class FailureCategory(
    val displayName: String,
    val recoverable: Boolean,
    val suggestedRecovery: String?,
    /** Failures in this category force a lower retrieval tier for safety. */
    val degradeRetrieval: Boolean = false,
) {
    SELECTOR_DRIFT(
        "Selector Drift",
        recoverable = true,
        suggestedRecovery = "Run htmlsnapshot inspect to re-discover selector",
    ),
    VISUAL_DRIFT(
        "Visual Drift",
        recoverable = true,
        suggestedRecovery = "Use PowerCSS :expr() geometric anchor as fallback",
    ),
    NETWORK(
        "Network",
        recoverable = true,
        suggestedRecovery = "Retry with backoff; check connectivity",
    ),
    AUTH_REQUIRED(
        "Auth Required",
        recoverable = false,
        suggestedRecovery = "Login wall detected; save auth state for reuse",
    ),
    PERMISSION_DENIED(
        "Permission Denied",
        recoverable = false,
        suggestedRecovery = "Page requires elevated access; cannot auto-recover",
    ),
    OVERLAY_BLOCKED(
        "Overlay Blocked",
        recoverable = true,
        suggestedRecovery = "Dismiss overlay (cookie consent, newsletter popup, etc.)",
    ),
    TIMING(
        "Timing",
        recoverable = true,
        suggestedRecovery = "Increase wait timeout; check for lazy loading",
    ),
    ANTI_BOT(
        "Anti-Bot",
        recoverable = false,
        suggestedRecovery = "CAPTCHA or bot detection triggered; extend min_probe_interval",
        degradeRetrieval = true,
    ),
    LAZY_LOADING(
        "Lazy Loading",
        recoverable = true,
        suggestedRecovery = "Scroll element into view before interaction; use data-src attribute",
    ),
    AB_EXPERIMENT(
        "A/B Experiment",
        recoverable = true,
        suggestedRecovery = "Selector not in current variant; use fallback selector",
    ),
    UNEXPECTED_REDIRECT(
        "Unexpected Redirect",
        recoverable = true,
        suggestedRecovery = "URL changed unexpectedly; verify current page matches expected",
    ),
    UNKNOWN(
        "Unknown",
        recoverable = false,
        suggestedRecovery = "Manual investigation required",
    );

    companion object {
        /**
         * Classify a failure from its error message and optional trace context.
         *
         * Uses keyword matching against the error message. The last selected
         * selector (from trace actions) is checked for additional context.
         */
        fun classify(errorMessage: String?, lastSelector: String? = null): FailureCategory {
            if (errorMessage.isNullOrBlank()) return UNKNOWN

            val msg = errorMessage.lowercase().trim()

            return when {
                // Anti-bot / CAPTCHA
                anyWordIn(msg, "captcha", "recaptcha", "hcaptcha", "turnstile",
                    "bot detection", "are you a robot", "verify you are human",
                    "unusual traffic", "automated access") -> ANTI_BOT

                // Auth
                anyWordIn(msg, "login", "sign in", "log in", "authenticate",
                    "unauthorized", "401", "403", "access denied", "forbidden") -> AUTH_REQUIRED

                // Permission
                anyWordIn(msg, "permission denied", "not allowed", "insufficient",
                    "elevated access", "admin only") -> PERMISSION_DENIED

                // Overlay
                anyWordIn(msg, "overlay", "modal", "popup", "dialog", "cookie",
                    "consent", "newsletter", "subscribe", "interstitial") -> OVERLAY_BLOCKED

                // Network
                anyWordIn(msg, "timeout", "timed out", "network", "connection",
                    "dns", "unreachable", "econnrefused", "econnreset",
                    "socket", "tls", "ssl", "certificate") -> NETWORK

                // Selector
                anyWordIn(msg, "selector", "element not found", "no element",
                    "cannot find", "not found", "missing", "no such element",
                    "queryselector", "css path") -> SELECTOR_DRIFT

                // Visual drift
                anyWordIn(msg, "not visible", "not clickable", "hidden",
                    "outside viewport", "covered", "obscured", "overlapping",
                    "position changed", "moved") -> VISUAL_DRIFT

                // Timing
                anyWordIn(msg, "wait", "loading", "still loading", "not ready",
                    "not loaded", "pending", "in progress", "slow") -> TIMING

                // Lazy loading
                anyWordIn(msg, "lazy", "lazy-load", "lazyload", "data-src",
                    "placeholder", "skeleton", "not yet loaded", "deferred") -> LAZY_LOADING

                // A/B experiment
                anyWordIn(msg, "a/b", "ab test", "variant", "experiment",
                    "split test", "different version") -> AB_EXPERIMENT

                // Redirect
                anyWordIn(msg, "redirect", "redirected", "moved", "relocated",
                    "url changed", "different page", "unexpected url") -> UNEXPECTED_REDIRECT

                else -> UNKNOWN
            }
        }

        private fun anyWordIn(text: String, vararg words: String): Boolean {
            return words.any { it in text }
        }
    }
}

// =============================================================================
// Verification Status
// =============================================================================

/**
 * The verification state of a [KnowledgeFacts] entry.
 *
 * Knowledge flows through this pipeline:
 * ```
 * Trace → ExperienceStats → Hypothesis → Candidate → Verified
 *                                                ↓
 *                                           Contested
 * ```
 *
 * Only [VERIFIED] knowledge is used for direct replay (P1 tier).
 * [HYPOTHESIS] is used as hints only (P3/P4 tier).
 * [CONTESTED] triggers re-verification.
 */
enum class VerificationStatus {
    /** Initial state after first deep learning pass. Confidence < 0.60. Not used for replay. */
    HYPOTHESIS,

    /** Verified by 2+ independent traces. Confidence 0.60–0.84. Verify-before-replay. */
    CANDIDATE,

    /** Dual-signal confirmed. Confidence ≥ 0.85. Direct replay. Selectors are LOCKED. */
    VERIFIED,

    /** Disconfirmations exceed confirmations. Under review. */
    CONTESTED,
}

// =============================================================================
// Promotion Level
// =============================================================================

/**
 * The abstraction level of a pattern in the promotion hierarchy.
 *
 * Patterns are promoted up the hierarchy as they are confirmed
 * across multiple sites in the same family, category, or universal class.
 */
enum class PromotionLevel(val minSitesRequired: Int) {
    /** Knowledge specific to a single domain. */
    SITE(1),

    /** Knowledge shared across similar sites (e.g., amazon-like: amazon, ebay, walmart). */
    FAMILY(2),

    /** Knowledge shared across a site category (e.g., marketplace: amazon, ebay, etsy). */
    CATEGORY(3),

    /** Knowledge shared across all sites of a universal class (e.g., ecommerce). */
    UNIVERSAL(4),
}
