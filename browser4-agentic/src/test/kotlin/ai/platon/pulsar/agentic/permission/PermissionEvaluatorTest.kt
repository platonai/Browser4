package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.model.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PermissionEvaluator")
class PermissionEvaluatorTest {

    private val analyzer = ToolCallPermissionAnalyzer()

    private fun makeRequest(
        domain: String,
        method: String,
        args: Map<String, Any?> = emptyMap(),
        agentId: String = "agent-1",
        sessionId: String = "session-1",
    ): PermissionRequest {
        val tc = ToolCall(domain, method, args.toMutableMap())
        return analyzer.analyze(tc, domain, agentId, sessionId)
    }

    // ---- Empty policy / default mode ----

    @Nested
    @DisplayName("default mode fallback")
    inner class DefaultMode {

        @Test
        @DisplayName("empty policy with ALLOW default → Allowed")
        fun emptyAllow() {
            val policy = PermissionPolicy(defaultMode = PermissionMode.ALLOW)
            val evaluator = PermissionEvaluator(policy)
            val req = makeRequest("tab", "click")
            val decision = evaluator.evaluate(req)
            assertTrue(decision is PermissionDecision.Allowed)
        }

        @Test
        @DisplayName("empty policy with DENY default → Denied")
        fun emptyDeny() {
            val policy = PermissionPolicy(defaultMode = PermissionMode.DENY)
            val evaluator = PermissionEvaluator(policy)
            val req = makeRequest("tab", "click")
            val decision = evaluator.evaluate(req)
            assertTrue(decision is PermissionDecision.Denied)
        }

        @Test
        @DisplayName("empty policy with ASK default → Ask")
        fun emptyAsk() {
            val policy = PermissionPolicy(defaultMode = PermissionMode.ASK)
            val evaluator = PermissionEvaluator(policy)
            val req = makeRequest("tab", "click")
            val decision = evaluator.evaluate(req)
            assertTrue(decision is PermissionDecision.Ask)
        }
    }

    // ---- Single rule matching ----

    @Nested
    @DisplayName("single rule matching")
    inner class SingleRule {

        @Test
        @DisplayName("exact domain+method match")
        fun exactMatch() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "tab", "click", PermissionMode.DENY)
            ))
            val evaluator = PermissionEvaluator(policy)
            val decision = evaluator.evaluate(makeRequest("tab", "click"))
            assertTrue(decision is PermissionDecision.Denied)
            assertEquals("r1", (decision as PermissionDecision.Denied).rule?.id)
        }

        @Test
        @DisplayName("wildcard domain matches any")
        fun wildcardDomain() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "*", "read", PermissionMode.DENY)
            ))
            val evaluator = PermissionEvaluator(policy)
            val decision = evaluator.evaluate(makeRequest("coding", "read"))
            assertTrue(decision is PermissionDecision.Denied)
        }

        @Test
        @DisplayName("wildcard method matches any")
        fun wildcardMethod() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "coding", "*", PermissionMode.DENY)
            ))
            val evaluator = PermissionEvaluator(policy)
            val decision = evaluator.evaluate(makeRequest("coding", "write"))
            assertTrue(decision is PermissionDecision.Denied)
        }

        @Test
        @DisplayName("alternation in method matches any listed")
        fun alternationMethod() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "tab", "open|navigate", PermissionMode.ASK)
            ))
            val evaluator = PermissionEvaluator(policy)
            assertTrue(evaluator.evaluate(makeRequest("tab", "open")) is PermissionDecision.Ask)
            assertTrue(evaluator.evaluate(makeRequest("tab", "navigate")) is PermissionDecision.Ask)
            assertTrue(evaluator.evaluate(makeRequest("tab", "click")) is PermissionDecision.Allowed)
        }

        @Test
        @DisplayName("non-matching domain is ignored (falls to default)")
        fun nonMatching() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "tab", "click", PermissionMode.DENY)
            ))
            val evaluator = PermissionEvaluator(policy)
            val decision = evaluator.evaluate(makeRequest("coding", "read"))
            assertTrue(decision is PermissionDecision.Allowed)
        }
    }

    // ---- Action class gating ----

    @Nested
    @DisplayName("action class gating")
    inner class ActionClassGating {

        @Test
        @DisplayName("rule with ACTION_CLASS=READ only matches READ requests")
        fun readOnlyRule() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "tab", "*", PermissionMode.ALLOW, actionClass = ActionClass.READ),
                PermissionRule("r2", "tab", "*", PermissionMode.DENY),
            ))
            val evaluator = PermissionEvaluator(policy)
            // tab.type is WRITE → matches r2 (DENY), not r1
            val writeReq = makeRequest("tab", "type")
            assertTrue(evaluator.evaluate(writeReq) is PermissionDecision.Denied)
            // tab.title is READ → matches both, r2 is more specific (DENY > ALLOW)
            // Actually: r2 (domain=tab, method=*, DENY) and r1 (domain=tab, method=*, ALLOW, actionClass=READ)
            // Both match tab.title. DENY > ALLOW → Denied
            val readReq = makeRequest("tab", "title")
            assertTrue(evaluator.evaluate(readReq) is PermissionDecision.Denied)
        }

        @Test
        @DisplayName("action class rule with higher specificity wins")
        fun actionClassSpecificity() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("allow-read", "tab", "*", PermissionMode.ALLOW, actionClass = ActionClass.READ),
                PermissionRule("deny-all", "tab", "*", PermissionMode.DENY),
            ))
            val evaluator = PermissionEvaluator(policy)
            // Both match, "allow-read" has actionClass specificity, but DENY > ALLOW
            val decision = evaluator.evaluate(makeRequest("tab", "title"))
            assertTrue(decision is PermissionDecision.Denied)
        }
    }

    // ---- Pattern matching ----

    @Nested
    @DisplayName("pattern matching")
    inner class PatternMatching {

        @Test
        @DisplayName("command pattern matches shell command")
        fun commandPattern() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule(
                    "no-force-push", "coding", "shell", PermissionMode.DENY,
                    pattern = "git push --force*", resource = ResourceType.COMMAND,
                    patternType = PatternType.GLOB,
                )
            ))
            val evaluator = PermissionEvaluator(policy)
            val forcePush = makeRequest("coding", "shell", mapOf("command" to "git push --force origin main"))
            assertTrue(evaluator.evaluate(forcePush) is PermissionDecision.Denied)

            val normalPush = makeRequest("coding", "shell", mapOf("command" to "git push origin main"))
            assertTrue(evaluator.evaluate(normalPush) is PermissionDecision.Allowed)
        }

        @Test
        @DisplayName("path pattern matches file path")
        fun pathPattern() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule(
                    "no-secrets", "coding", "read", PermissionMode.DENY,
                    pattern = "**/.env", resource = ResourceType.PATH,
                    patternType = PatternType.GLOB,
                )
            ))
            val evaluator = PermissionEvaluator(policy)
            val envFile = makeRequest("coding", "read", mapOf("path" to "project/.env"))
            assertTrue(evaluator.evaluate(envFile) is PermissionDecision.Denied)

            val normalFile = makeRequest("coding", "read", mapOf("path" to "project/README.md"))
            assertTrue(evaluator.evaluate(normalFile) is PermissionDecision.Allowed)
        }

        @Test
        @DisplayName("url pattern matches navigation URL")
        fun urlPattern() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule(
                    "no-external", "tab", "navigate", PermissionMode.ASK,
                    pattern = "https://*", resource = ResourceType.URL,
                    patternType = PatternType.GLOB,
                )
            ))
            val evaluator = PermissionEvaluator(policy)
            val external = makeRequest("tab", "navigate", mapOf("url" to "https://google.com"))
            assertTrue(evaluator.evaluate(external) is PermissionDecision.Ask)

            val local = makeRequest("tab", "navigate", mapOf("url" to "http://localhost:8080"))
            assertTrue(evaluator.evaluate(local) is PermissionDecision.Allowed)
        }
    }

    // ---- Scope matching ----

    @Nested
    @DisplayName("scope matching")
    inner class ScopeMatching {

        @Test
        @DisplayName("AGENT-scoped rule only matches specific agent")
        fun agentScoped() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "coding", "write", PermissionMode.DENY,
                    scope = RuleScope.AGENT, scopeValue = "agent-dev"),
                PermissionRule("r2", "coding", "write", PermissionMode.ALLOW),
            ))
            val evaluator = PermissionEvaluator(policy)
            // Non-matching agent → r1 skipped, r2 ALLOW
            val otherAgent = makeRequest("coding", "write", agentId = "agent-other")
            assertTrue(evaluator.evaluate(otherAgent) is PermissionDecision.Allowed)

            // Matching agent → r1 DENY wins (scoped > global)
            val devAgent = makeRequest("coding", "write", agentId = "agent-dev")
            assertTrue(evaluator.evaluate(devAgent) is PermissionDecision.Denied)
        }

        @Test
        @DisplayName("glob pattern in scope value")
        fun globScopeValue() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("r1", "coding", "write", PermissionMode.DENY,
                    scope = RuleScope.AGENT, scopeValue = "*dev*"),
            ))
            val evaluator = PermissionEvaluator(policy)
            assertTrue(evaluator.evaluate(
                makeRequest("coding", "write", agentId = "agent-dev")) is PermissionDecision.Denied)
            assertTrue(evaluator.evaluate(
                makeRequest("coding", "write", agentId = "backend-dev-2")) is PermissionDecision.Denied)
            assertTrue(evaluator.evaluate(
                makeRequest("coding", "write", agentId = "agent-qa")) is PermissionDecision.Allowed)
        }
    }

    // ---- explain() ----

    @Nested
    @DisplayName("explain()")
    inner class Explain {

        @Test
        @DisplayName("explain identifies matching rule")
        fun matchingRule() {
            val policy = PermissionPolicy(rules = listOf(
                PermissionRule("no-write", "coding", "write", PermissionMode.DENY, reason = "read-only mode"),
            ))
            val evaluator = PermissionEvaluator(policy)
            val explanation = evaluator.explain(makeRequest("coding", "write"))
            assertTrue(explanation.contains("DENY"))
            assertTrue(explanation.contains("no-write"))
        }

        @Test
        @DisplayName("explain shows default when no rule matches")
        fun noMatch() {
            val policy = PermissionPolicy(name = "test-policy", defaultMode = PermissionMode.ALLOW)
            val evaluator = PermissionEvaluator(policy)
            val explanation = evaluator.explain(makeRequest("tab", "click"))
            assertTrue(explanation.contains("ALLOW"))
            assertTrue(explanation.contains("test-policy"))
        }
    }
}
