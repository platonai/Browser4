package ai.platon.pulsar.agentic.tools.experience

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Verified immutable knowledge for a `(domain, intent)` pair.
 *
 * KnowledgeFacts are **never modified after reaching VERIFIED status**.
 * They are stored in `knowledge/facts/<domain>/` and are the authoritative
 * source of truth for replay.
 *
 * Before verification, facts exist as HYPOTHESIS or CANDIDATE.
 * After verification, selectors are LOCKED (immutable).
 *
 * @see ExperienceStats for mutable aggregated statistics
 * @see TraceRecord for raw execution records
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KnowledgeFacts(
    @JsonProperty("intent") val intent: String,
    @JsonProperty("domain") val domain: String,
    @JsonProperty("url_pattern") val urlPattern: String,
    @JsonProperty("status") val status: VerificationStatus = VerificationStatus.HYPOTHESIS,

    @JsonProperty("site_facts") val siteFacts: SiteFacts = SiteFacts(domain),
    @JsonProperty("page_facts") val pageFacts: PageFacts = PageFacts(),
    @JsonProperty("selectors") val selectors: Map<String, VerifiedSelector> = emptyMap(),

    @JsonProperty("interaction_hints") val interactionHints: List<String> = emptyList(),
    @JsonProperty("known_blockers") val knownBlockers: List<BlockerInfo> = emptyList(),
    @JsonProperty("anti_patterns") val antiPatterns: List<String> = emptyList(),

    @JsonProperty("promotion_history") val promotionHistory: List<PromotionEvent> = emptyList(),
    @JsonProperty("created_at") val createdAt: Instant = Instant.now(),
    @JsonProperty("updated_at") val updatedAt: Instant = Instant.now(),
) {
    companion object {
        fun createHypothesis(intent: String, domain: String, urlPattern: String): KnowledgeFacts {
            return KnowledgeFacts(
                intent = intent,
                domain = domain,
                urlPattern = urlPattern,
                status = VerificationStatus.HYPOTHESIS,
                promotionHistory = listOf(
                    PromotionEvent(
                        from = null,
                        to = "hypothesis",
                        reason = "Initial deep learning pass",
                    )
                ),
            )
        }
    }
}

// =============================================================================
// Site Facts — immutable site-level knowledge
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SiteFacts(
    @JsonProperty("domain") val domain: String,
    @JsonProperty("site_family") val siteFamily: String? = null,
    @JsonProperty("site_category") val siteCategory: String? = null,
    @JsonProperty("site_universal") val siteUniversal: String? = null,
    @JsonProperty("auth_pattern") val authPattern: String? = null,
    @JsonProperty("tech_stack") val techStack: String? = null,
    @JsonProperty("load_strategy") val loadStrategy: String? = null,
)

// =============================================================================
// Page Facts — immutable page-level knowledge
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageFacts(
    @JsonProperty("landmarks") val landmarks: List<String> = emptyList(),
    @JsonProperty("page_type") val pageType: String? = null,
    @JsonProperty("dynamic_load") val dynamicLoad: String? = null,
    @JsonProperty("load_wait") val loadWait: String? = null,
)

// =============================================================================
// Verified Selector — locked after promotion
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VerifiedSelector(
    @JsonProperty("primary") val primary: String,
    @JsonProperty("fallbacks") val fallbacks: List<String> = emptyList(),
    @JsonProperty("source") val source: String = "css",
    @JsonProperty("note") val note: String? = null,
)

// =============================================================================
// Blocker Info — known page blockers
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BlockerInfo(
    @JsonProperty("type") val type: String,
    @JsonProperty("selector") val selector: String? = null,
    @JsonProperty("action") val action: String = "click",
    @JsonProperty("frequency") val frequency: String? = null,
    @JsonProperty("note") val note: String? = null,
)

// =============================================================================
// Promotion Event — record of a status change
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PromotionEvent(
    @JsonProperty("from") val from: String?,
    @JsonProperty("to") val to: String,
    @JsonProperty("date") val date: Instant = Instant.now(),
    @JsonProperty("reason") val reason: String? = null,
    @JsonProperty("verified_visits") val verifiedVisits: Int? = null,
    @JsonProperty("dual_signal_passed") val dualSignalPassed: Boolean? = null,
)

// =============================================================================
// Pattern Promotion — cross-site generalization
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PatternPromotion(
    @JsonProperty("level") val level: PromotionLevel,
    @JsonProperty("confirmed_sites") val confirmedSites: List<String> = emptyList(),
    @JsonProperty("disconfirmed_sites") val disconfirmedSites: List<String> = emptyList(),
    @JsonProperty("confidence") val confidence: Double = 0.0,
) {
    /** Can this pattern be promoted to the next level? */
    val canPromote: Boolean
        get() {
            val total = confirmedSites.size + disconfirmedSites.size
            if (total == 0) return false
            return confirmedSites.size >= level.minSitesRequired &&
                confirmedSites.size.toDouble() / total >= 0.75
        }
}
