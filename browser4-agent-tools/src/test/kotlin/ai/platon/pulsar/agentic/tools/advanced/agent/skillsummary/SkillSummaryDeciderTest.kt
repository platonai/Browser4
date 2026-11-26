package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("Unit")
@Tag("Fast")
class SkillSummaryDeciderTest {

    private fun ctx(
        prompt: String = "refactor the auth module",
        rounds: Long = 5,
        toolCalls: Int = 10,
        mutations: Long = 2,
        toolKinds: List<String> = emptyList(),
        strategySwitches: Int = 0,
        verbatimRetries: Int = 0,
    ) = TaskSummaryContext(
        taskId = "t", prompt = prompt, result = null, workspace = null,
        rounds = rounds, toolCalls = toolCalls, mutations = mutations,
        strategySwitches = strategySwitches,
        verbatimRetries = verbatimRetries,
        toolCounts = toolKinds.associateWith { 1L }, toolSequence = emptyList(),
        verifyVerdict = null, filesChanged = emptyList(),
        inputTokens = 0, outputTokens = 0, durationMs = null,
    )

    private fun kinds(vararg names: String) = names.toList()

    private fun decider(
        index: SkillFingerprintIndex,
        config: SkillSummaryConfig = SkillSummaryConfig(enabled = true),
    ) = SkillSummaryDecider(index, config)

    @Test
    @DisplayName("TR-3.1 rounds below threshold → skip")
    fun skipOnLowRounds(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val d = decider(idx)
        val decision = d.decide(ctx(rounds = 0))
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
        assertNull(decision.targetSkillId)
    }

    @Test
    @DisplayName("TR-3.1 toolCalls below threshold → skip")
    fun skipOnLowToolCalls(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val decision = decider(idx).decide(ctx(toolCalls = 1))
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 single-pass browse run scores below the bar → skip")
    fun skipOnSinglePassBrowseRun(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        // kinds 2 + 0 switches - 0 verbatim = 2 < 6
        val decision = decider(idx).decide(
            ctx(rounds = 1, toolCalls = 10, mutations = 0, toolKinds = kinds("goto", "snapshot")),
        )
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 browse run with many distinct tools → generate")
    fun generateForDiverseBrowseRun(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        // kinds 6 = 6 >= 6
        val decision = decider(idx).decide(
            ctx(
                rounds = 1, toolCalls = 22, mutations = 0,
                toolKinds = kinds("goto", "snapshot", "click", "type", "scroll", "extract"),
            ),
        )
        assertEquals(SummaryDecision.Action.GENERATE, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 browse run with a strategy switch → generate")
    fun generateForStrategySwitch(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        // kinds 3 + 3 * 1 switch = 6 >= 6
        val decision = decider(idx).decide(
            ctx(
                rounds = 1, toolCalls = 8, mutations = 0,
                toolKinds = kinds("goto", "click", "snapshot"),
                strategySwitches = 1,
            ),
        )
        assertEquals(SummaryDecision.Action.GENERATE, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 verbatim retry loop is penalised → skip")
    fun skipOnVerbatimRetryLoop(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        // kinds 4 - 2 * 3 verbatim retries = -2 < 6
        val decision = decider(idx).decide(
            ctx(
                rounds = 1, toolCalls = 12, mutations = 0,
                toolKinds = kinds("goto", "click", "snapshot", "scroll"),
                verbatimRetries = 3,
            ),
        )
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 a run with file mutations still generates like before")
    fun generateForMutationRun(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val decision = decider(idx).decide(
            ctx(rounds = 1, toolCalls = 6, mutations = 1, toolKinds = kinds("coding_write")),
        )
        assertEquals(SummaryDecision.Action.GENERATE, decision.action)
    }

    @Test
    @DisplayName("TR-3.2 requireMutation=false with 0 mutations → passes to generate")
    fun noMutationAllowedWhenDisabled(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val decision = decider(idx, SkillSummaryConfig(enabled = true, requireMutation = false))
            .decide(ctx(mutations = 0))
        assertEquals(SummaryDecision.Action.GENERATE, decision.action)
    }

    @Test
    @DisplayName("empty prompt fingerprint → skip")
    fun skipOnEmptyFingerprint(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val decision = decider(idx).decide(ctx(prompt = "the a an"))
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
    }

    @Test
    @DisplayName("TR-3.4 dissimilar prompt with no prior index → generate")
    fun generateWhenNoMatch(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val decision = decider(idx).decide(ctx(prompt = "crawl product prices from amazon"))
        assertEquals(SummaryDecision.Action.GENERATE, decision.action)
        assertNull(decision.targetSkillId)
    }

    @Test
    @DisplayName("TR-3.4 similar prompt to an indexed skill → append with targetSkillId")
    fun appendWhenSimilar(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        idx.record(SkillFingerprint.tokensOf("refactor the auth module"), "refactor-auth")
        // cooldownHours=0 so the cooldown branch is disabled; this test asserts similarity → append
        val cfg = SkillSummaryConfig(enabled = true, cooldownHours = 0)
        // token set {refactor, auth, module} identical → jaccard 1.0 ≥ 0.85 → append
        val decision = decider(idx, cfg).decide(ctx(prompt = "refactor auth module"))
        assertEquals(SummaryDecision.Action.APPEND, decision.action)
        assertEquals("refactor-auth", decision.targetSkillId)
    }

    @Test
    @DisplayName("similar prompt within cooldown → skip (no duplicate, no append)")
    fun skipWhenSimilarButCooling(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val t0 = 1_000_000L
        idx.record(SkillFingerprint.tokensOf("refactor the auth module"), "refactor-auth", now = t0)
        // default cooldownHours=6; hit at t0+1h is still cooling → skip
        val decision = decider(idx).decide(ctx(prompt = "refactor auth module"), now = t0 + 3_600_000)
        assertEquals(SummaryDecision.Action.SKIP, decision.action)
    }

    @Test
    @DisplayName("similar prompt after cooldown → append")
    fun appendWhenSimilarAfterCooldown(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val t0 = 1_000_000L
        idx.record(SkillFingerprint.tokensOf("refactor the auth module"), "refactor-auth", now = t0)
        val decision = decider(idx)
            .decide(ctx(prompt = "refactor auth module"), now = t0 + 7 * 3_600_000)
        assertEquals(SummaryDecision.Action.APPEND, decision.action)
        assertEquals("refactor-auth", decision.targetSkillId)
    }
}
