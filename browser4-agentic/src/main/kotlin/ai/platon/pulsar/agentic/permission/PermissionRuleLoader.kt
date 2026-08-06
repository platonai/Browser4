package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.common.getLogger
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Loads [PermissionPolicy] instances from YAML and JSON files.
 *
 * Supports two shapes:
 * - **Single-policy**: top-level keys `version`, `name`, `default_mode`, `rules`
 * - **Named-policies**: top-level key `policies` containing a list, each with optional `applies_to`
 *
 * YAML keys use snake_case (e.g. `default_mode`, `action_class`, `pattern_type`).
 * JSON files use the same structure.
 */
object PermissionRuleLoader {

    private val logger = getLogger(PermissionRuleLoader::class)
    private val yaml = Yaml()

    // ---- public API ----

    /**
     * Loads a single policy from a .yaml/.yml/.json file.
     */
    fun loadPolicyFile(path: Path): PermissionPolicy? {
        if (!path.isRegularFile() || !path.exists()) return null
        val content = try {
            Files.readString(path)
        } catch (e: Exception) {
            logger.warn("Failed to read permission file: $path — ${e.message}")
            return null
        }
        return loadFromString(content).values.firstOrNull()
    }

    /**
     * Loads named policies from a .yaml/.yml/.json file.
     *
     * @return map from policy name to [PermissionPolicy]
     */
    fun loadPoliciesFile(path: Path): Map<String, PermissionPolicy> {
        if (!path.isRegularFile() || !path.exists()) return emptyMap()
        val content = try {
            Files.readString(path)
        } catch (e: Exception) {
            logger.warn("Failed to read permission file: $path — ${e.message}")
            return emptyMap()
        }
        return loadFromString(content)
    }

    /**
     * Parses a YAML or JSON string into a map of named policies.
     *
     * Handles both the single-policy shape (wraps in a map with the policy's name)
     * and the named-policies shape (keyed by name).
     */
    @Suppress("UNCHECKED_CAST")
    fun loadFromString(content: String): Map<String, PermissionPolicy> {
        val data = try {
            yaml.load<Map<String, Any?>>(content)
        } catch (e: Exception) {
            logger.warn("Failed to parse permission YAML/JSON: ${e.message}")
            return emptyMap()
        }
        if (data == null) return emptyMap()

        return when {
            // Named-policies shape: { policies: [...] }
            data.containsKey("policies") -> {
                val policies = data["policies"] as? List<Map<String, Any?>>
                    ?: return emptyMap()
                policies.mapNotNull { parsePolicy(it) }.associateBy { it.name }
            }
            // Single-policy shape: { version, name, ... rules: [...] }
            data.containsKey("version") || data.containsKey("rules") -> {
                val policy = parsePolicy(data) ?: return emptyMap()
                mapOf(policy.name to policy)
            }
            else -> emptyMap()
        }
    }

    /**
     * Resolves the effective policy for an agent by searching in order:
     * 1. `BROWSER4_PERMISSIONS_FILE` environment variable
     * 2. `~/.browser4/permissions.yaml`
     * 3. `<agentBaseDir>/permissions.yaml`
     *
     * Returns the first successfully loaded policy, or null.
     */
    fun resolveEffectivePolicy(baseDir: Path, agentId: String): PermissionPolicy? {
        // 1. Explicit env var
        val envFile = System.getenv("BROWSER4_PERMISSIONS_FILE")
            ?: System.getProperty("browser4.permissions.file")
        if (envFile != null) {
            val policy = loadPolicyFile(Path.of(envFile))
            if (policy != null) return policy
        }

        // 2. Global user policy
        val userHome = Path.of(System.getProperty("user.home", ""))
        val globalFile = userHome.resolve(".browser4").resolve("permissions.yaml")
        val globalPolicy = loadPolicyFile(globalFile)
        if (globalPolicy != null) return globalPolicy

        // 3. Per-agent policy
        val agentFile = baseDir.resolve("permissions.yaml")
        return loadPolicyFile(agentFile)
    }

    // ---- internal ----

    @Suppress("UNCHECKED_CAST")
    private fun parsePolicy(data: Map<String, Any?>): PermissionPolicy? {
        val name = data["name"] as? String ?: "default"
        val defaultMode = parseMode(data["default_mode"]?.toString()) ?: PermissionMode.ALLOW

        val rulesList = data["rules"] as? List<Map<String, Any?>>
            ?: (data["rules"] as? List<Any?>)?.filterIsInstance<Map<String, Any?>>()
            ?: emptyList()

        val rules = rulesList.mapNotNull { parseRule(it) }

        // Handle applies_to → add scope rules
        val appliesTo = (data["applies_to"] as? List<String>).orEmpty()
        val scopedRules = rules.flatMap { rule ->
            if (appliesTo.isEmpty()) listOf(rule)
            else appliesTo.map { agentPattern ->
                rule.copy(scope = RuleScope.AGENT, scopeValue = agentPattern)
            }
        }

        return PermissionPolicy(
            version = (data["version"] as? Number)?.toInt() ?: 1,
            name = name,
            defaultMode = defaultMode,
            rules = scopedRules,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRule(data: Map<String, Any?>): PermissionRule? {
        val id = data["id"]?.toString() ?: return null
        val domain = data["domain"]?.toString() ?: "*"
        val method = data["method"]?.toString() ?: "*"
        val mode = parseMode(data["mode"]?.toString()) ?: return null

        return PermissionRule(
            id = id,
            domain = domain,
            method = method,
            mode = mode,
            actionClass = parseActionClass(data["action_class"]?.toString()),
            pattern = data["pattern"]?.toString(),
            resource = parseResourceType(data["resource"]?.toString()),
            patternType = parsePatternType(data["pattern_type"]?.toString()),
            priority = (data["priority"] as? Number)?.toInt() ?: 0,
            scope = parseScope(data["scope"]?.toString()),
            scopeValue = data["scope_value"]?.toString(),
            reason = data["reason"]?.toString(),
        )
    }

    private fun parseMode(s: String?): PermissionMode? {
        return when (s?.lowercase()) {
            "allow" -> PermissionMode.ALLOW
            "ask" -> PermissionMode.ASK
            "deny" -> PermissionMode.DENY
            else -> null
        }
    }

    private fun parseActionClass(s: String?): ActionClass {
        return when (s?.lowercase()) {
            "read" -> ActionClass.READ
            "write" -> ActionClass.WRITE
            "destructive" -> ActionClass.DESTRUCTIVE
            "navigate" -> ActionClass.NAVIGATE
            "search" -> ActionClass.SEARCH
            "git" -> ActionClass.GIT
            "dev_tool", "devtool" -> ActionClass.DEV_TOOL
            "external_access", "externalaccess" -> ActionClass.EXTERNAL_ACCESS
            else -> ActionClass.ANY
        }
    }

    private fun parseResourceType(s: String?): ResourceType {
        return when (s?.lowercase()) {
            "command" -> ResourceType.COMMAND
            "path" -> ResourceType.PATH
            "url" -> ResourceType.URL
            "script" -> ResourceType.SCRIPT
            else -> ResourceType.NONE
        }
    }

    private fun parsePatternType(s: String?): PatternType {
        return when (s?.lowercase()) {
            "exact" -> PatternType.EXACT
            "glob" -> PatternType.GLOB
            "regex" -> PatternType.REGEX
            else -> PatternType.GLOB
        }
    }

    private fun parseScope(s: String?): RuleScope {
        return when (s?.lowercase()) {
            "agent" -> RuleScope.AGENT
            "session" -> RuleScope.SESSION
            else -> RuleScope.GLOBAL
        }
    }
}
