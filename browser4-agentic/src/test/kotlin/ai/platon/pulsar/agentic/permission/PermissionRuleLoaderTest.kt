package ai.platon.pulsar.agentic.permission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@DisplayName("PermissionRuleLoader")
class PermissionRuleLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- Single policy YAML ----

    @Nested
    @DisplayName("single policy YAML")
    inner class SinglePolicyYaml {

        @Test
        @DisplayName("loads a basic single-policy YAML")
        fun basicSinglePolicy() {
            val yaml = """
                version: 1
                name: test-policy
                default_mode: deny
                rules:
                  - id: no-write
                    domain: coding
                    method: write
                    mode: deny
                    reason: "No writes allowed"
                  - id: allow-read
                    domain: coding
                    method: "read|glob|grep"
                    mode: allow
            """.trimIndent()

            val policies = PermissionRuleLoader.loadFromString(yaml)
            assertEquals(1, policies.size)
            val policy = policies["test-policy"]!!
            assertEquals("test-policy", policy.name)
            assertEquals(PermissionMode.DENY, policy.defaultMode)
            assertEquals(2, policy.rules.size)
            assertEquals("no-write", policy.rules[0].id)
            assertEquals(PermissionMode.DENY, policy.rules[0].mode)
        }

        @Test
        @DisplayName("loads policy with pattern and action_class")
        fun withPatternAndActionClass() {
            val yaml = """
                version: 1
                name: advanced
                default_mode: allow
                rules:
                  - id: git-ask
                    domain: coding
                    method: shell
                    mode: ask
                    pattern: "git push*"
                    resource: command
                    pattern_type: glob
                    action_class: git
                    reason: "Git push requires confirmation"
            """.trimIndent()

            val policies = PermissionRuleLoader.loadFromString(yaml)
            val rule = policies["advanced"]!!.rules[0]
            assertEquals("git push*", rule.pattern)
            assertEquals(ResourceType.COMMAND, rule.resource)
            assertEquals(PatternType.GLOB, rule.patternType)
            assertEquals(ActionClass.GIT, rule.actionClass)
        }
    }

    // ---- Named policies YAML ----

    @Nested
    @DisplayName("named policies YAML")
    inner class NamedPoliciesYaml {

        @Test
        @DisplayName("loads multiple named policies")
        fun multiplePolicies() {
            val yaml = """
                version: 1
                policies:
                  - name: code-analysis
                    applies_to:
                      - "*analysis*"
                    default_mode: allow
                    rules:
                      - id: analysis.no-write
                        domain: coding
                        method: write
                        mode: deny
                  - name: dev-agent
                    applies_to:
                      - "*dev*"
                    default_mode: allow
                    rules:
                      - id: dev.no-force-push
                        domain: coding
                        method: shell
                        mode: deny
                        pattern: "git push --force*"
                        resource: command
                        pattern_type: glob
            """.trimIndent()

            val policies = PermissionRuleLoader.loadFromString(yaml)
            assertEquals(2, policies.size)
            assertTrue(policies.containsKey("code-analysis"))
            assertTrue(policies.containsKey("dev-agent"))

            // applies_to → scope on rules
            val analysisRules = policies["code-analysis"]!!.rules
            assertEquals(1, analysisRules.size)
            analysisRules.forEach {
                assertEquals(RuleScope.AGENT, it.scope)
                assertEquals("*analysis*", it.scopeValue)
            }
        }
    }

    // ---- File loading ----

    @Nested
    @DisplayName("file loading")
    inner class FileLoading {

        @Test
        @DisplayName("loads policy from a YAML file")
        fun loadFromFile() {
            val file = tempDir.resolve("permissions.yaml")
            file.writeText("""
                version: 1
                name: file-policy
                default_mode: allow
                rules:
                  - id: r1
                    domain: "*"
                    method: "*"
                    mode: allow
            """.trimIndent())

            val policy = PermissionRuleLoader.loadPolicyFile(file)
            assertNotNull(policy)
            assertEquals("file-policy", policy!!.name)
        }

        @Test
        @DisplayName("returns null for non-existent file")
        fun nonExistentFile() {
            val policy = PermissionRuleLoader.loadPolicyFile(tempDir.resolve("nonexistent.yaml"))
            assertNull(policy)
        }
    }

    // ---- Error handling ----

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {

        @Test
        @DisplayName("malformed YAML returns empty map")
        fun malformedYaml() {
            val policies = PermissionRuleLoader.loadFromString("::: not valid yaml ::: {{{")
            assertTrue(policies.isEmpty())
        }

        @Test
        @DisplayName("empty string returns empty map")
        fun emptyString() {
            val policies = PermissionRuleLoader.loadFromString("")
            assertTrue(policies.isEmpty())
        }

        @Test
        @DisplayName("rules without id are silently skipped")
        fun rulesWithoutId() {
            val yaml = """
                version: 1
                name: partial
                default_mode: allow
                rules:
                  - mode: deny
                    domain: coding
                    method: write
                  - id: valid-rule
                    domain: tab
                    method: click
                    mode: deny
            """.trimIndent()

            val policies = PermissionRuleLoader.loadFromString(yaml)
            val rules = policies["partial"]!!.rules
            assertEquals(1, rules.size)
            assertEquals("valid-rule", rules[0].id)
        }
    }
}
