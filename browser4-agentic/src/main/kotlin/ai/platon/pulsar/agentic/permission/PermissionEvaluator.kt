package ai.platon.pulsar.agentic.permission

/**
 * Evaluates [PermissionRequest]s against a [PermissionPolicy] to produce a [PermissionDecision].
 *
 * ## Precedence model
 *
 * 1. Scoped rules outrank global rules: AGENT > SESSION > GLOBAL
 * 2. Within the same scope, specificity wins:
 *    exact domain+method > domain+method:* > domain:* > *:*
 * 3. Rules with a matching pattern / actionClass outrank bare ones
 * 4. On tie: DENY > ASK > ALLOW
 * 5. Higher [PermissionRule.priority] breaks remaining ties
 * 6. Fallback: [PermissionPolicy.defaultMode]
 *
 * @param policy the permission policy to evaluate against
 */
class PermissionEvaluator(private val policy: PermissionPolicy) {

    /**
     * Evaluates [request] and returns the effective decision.
     */
    fun evaluate(request: PermissionRequest): PermissionDecision {
        val matchingRules = policy.rules.filter { ruleMatches(it, request) }

        if (matchingRules.isEmpty()) {
            return when (policy.defaultMode) {
                PermissionMode.ALLOW -> PermissionDecision.Allowed(
                    null, "no matching rule; default allow"
                )
                PermissionMode.ASK -> PermissionDecision.Ask(
                    PermissionRule(
                        id = "default-ask",
                        domain = "*",
                        mode = PermissionMode.ASK,
                        reason = "default policy is ASK",
                    ),
                    request,
                )
                PermissionMode.DENY -> PermissionDecision.Denied(
                    null, "no matching rule; default deny"
                )
            }
        }

        // Pick the most specific matching rule
        val bestRule = matchingRules.maxWith(ruleComparator(request))

        return when (bestRule.mode) {
            PermissionMode.ALLOW -> PermissionDecision.Allowed(
                bestRule, bestRule.reason ?: "allowed by rule '${bestRule.id}'"
            )
            PermissionMode.ASK -> PermissionDecision.Ask(bestRule, request)
            PermissionMode.DENY -> PermissionDecision.Denied(
                bestRule, bestRule.reason ?: "denied by rule '${bestRule.id}'"
            )
        }
    }

    /**
     * Returns a human-readable explanation of why [request] would be allowed/denied/asked.
     */
    fun explain(request: PermissionRequest): String {
        val matchingRules = policy.rules.filter { ruleMatches(it, request) }
        if (matchingRules.isEmpty()) {
            return "'${request.domain}.${request.method}' → ${policy.defaultMode.name} (default policy: ${policy.name})"
        }
        val bestRule = matchingRules.maxWith(ruleComparator(request))
        return "'${request.domain}.${request.method}' → ${bestRule.mode.name} — matches rule '${bestRule.id}'${bestRule.reason?.let { ": $it" } ?: ""}"
    }

    // ---- rule matching ----

    private fun ruleMatches(rule: PermissionRule, request: PermissionRequest): Boolean {
        if (!domainMatches(rule.domain, request.domain)) return false
        if (!methodMatches(rule.method, request.method)) return false
        if (!actionClassMatches(rule.actionClass, request.actionClass)) return false
        if (!scopeMatches(rule, request)) return false
        if (!patternMatches(rule, request)) return false
        return true
    }

    private fun domainMatches(ruleDomain: String, requestDomain: String): Boolean {
        if (ruleDomain == "*") return true
        return ruleDomain.split("|").any { it.trim() == requestDomain }
    }

    private fun methodMatches(ruleMethod: String, requestMethod: String): Boolean {
        if (ruleMethod == "*") return true
        return ruleMethod.split("|").any { it.trim() == requestMethod }
    }

    private fun actionClassMatches(ruleAc: ActionClass, requestAc: ActionClass): Boolean {
        if (ruleAc == ActionClass.ANY) return true
        return ruleAc == requestAc
    }

    private fun scopeMatches(rule: PermissionRule, request: PermissionRequest): Boolean {
        return when (rule.scope) {
            RuleScope.GLOBAL -> true
            RuleScope.AGENT -> scopeValueMatches(rule.scopeValue, request.agentId)
            RuleScope.SESSION -> scopeValueMatches(rule.scopeValue, request.sessionId)
        }
    }

    private fun scopeValueMatches(ruleValue: String?, actualValue: String): Boolean {
        if (ruleValue == null) return true
        // Support glob patterns in scope values (e.g. "*dev*", "*analysis*")
        if (ruleValue.contains('*') || ruleValue.contains('?')) {
            return PatternMatcher.matches(
                ruleValue, actualValue, PatternType.GLOB, ResourceType.NONE
            )
        }
        return ruleValue.equals(actualValue, ignoreCase = true)
    }

    private fun patternMatches(rule: PermissionRule, request: PermissionRequest): Boolean {
        val pattern = rule.pattern ?: return true
        val actualValue = when (rule.resource) {
            ResourceType.COMMAND -> request.command ?: return false
            ResourceType.PATH -> request.path ?: return false
            ResourceType.URL -> request.url ?: return false
            ResourceType.SCRIPT -> request.script ?: return false
            ResourceType.NONE -> return true
        }
        return PatternMatcher.matches(pattern, actualValue, rule.patternType, rule.resource)
    }

    // ---- specificity comparator ----

    private fun ruleComparator(request: PermissionRequest): Comparator<PermissionRule> {
        return compareBy<PermissionRule>(
            // 1. DENY > ASK > ALLOW (higher = more restrictive → wins)
            { modeScore(it.mode) },
            // 2. Scoped rules outrank global
            { scopeScore(it.scope) },
            // 3. Specificity: exact domain better than wildcard
            { domainSpecificity(it.domain) },
            // 4. Specificity: exact method better than wildcard
            { methodSpecificity(it.method) },
            // 5. Rule has a pattern that matches the request
            { if (it.pattern != null && it.resource != ResourceType.NONE && requestMatchesPattern(it, request)) 1 else 0 },
            // 6. Rule has an actionClass filter
            { if (it.actionClass != ActionClass.ANY) 1 else 0 },
            // 7. Explicit priority (higher wins)
            { it.priority },
        )
    }

    private fun requestMatchesPattern(rule: PermissionRule, request: PermissionRequest): Boolean {
        val pattern = rule.pattern ?: return false
        val actualValue = when (rule.resource) {
            ResourceType.COMMAND -> request.command ?: return false
            ResourceType.PATH -> request.path ?: return false
            ResourceType.URL -> request.url ?: return false
            ResourceType.SCRIPT -> request.script ?: return false
            ResourceType.NONE -> return false
        }
        return PatternMatcher.matches(pattern, actualValue, rule.patternType, rule.resource)
    }

    private fun modeScore(mode: PermissionMode): Int = when (mode) {
        PermissionMode.DENY -> 3
        PermissionMode.ASK -> 2
        PermissionMode.ALLOW -> 1
    }

    private fun scopeScore(scope: RuleScope): Int = when (scope) {
        RuleScope.AGENT -> 3
        RuleScope.SESSION -> 2
        RuleScope.GLOBAL -> 1
    }

    private fun domainSpecificity(domain: String): Int {
        return when {
            domain == "*" -> 0
            domain.contains("|") -> 1   // alternation → less specific than exact
            else -> 2                    // exact domain
        }
    }

    private fun methodSpecificity(method: String): Int {
        return when {
            method == "*" -> 0
            method.contains("|") -> 1
            else -> 2
        }
    }
}
