package ai.platon.pulsar.agentic.skills.builtin

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata

/**
 * Built-in skill for web navigation tasks.
 *
 * Capabilities:
 * - Navigate to URLs
 * - Navigate back/forward in browser history
 * - Refresh pages
 * - Wait for page load
 *
 * Parameters:
 * - action: "navigate" | "back" | "forward" | "refresh" (required)
 * - url: Target URL for navigation (required for "navigate" action)
 * - waitForSelector: CSS selector to wait for after navigation (optional)
 * - timeout: Maximum wait time in milliseconds (optional, default 30000)
 */
class NavigationSkill : AbstractSkill(
    metadata = SkillMetadata(
        name = "navigation",
        version = "1.0.0",
        description = "Navigate web pages, manage browser history, and wait for page loads",
        tags = setOf("navigation", "browser", "web"),
        requiredTools = setOf("driver.navigateTo", "driver.goBack", "driver.goForward", "driver.reload")
    )
) {

    override suspend fun execute(context: SkillContext): ActResult {
        val action = getRequiredParameter(context, "action") as String
        val session = context.session
        val driver = session.getOrCreateBoundDriver()

        return when (action.lowercase()) {
            "navigate" -> {
                val url = getRequiredParameter(context, "url") as String
                driver.navigateTo(url)
                
                val waitForSelector = getOptionalParameter<String?>(context, "waitForSelector", null)
                if (waitForSelector != null) {
                    val timeout = getOptionalParameter(context, "timeout", 30000L) as Long
                    driver.waitForSelector(waitForSelector, timeout)
                }
                
                ActResult(
                    success = true,
                    message = "Successfully navigated to: $url",
                    action = "navigation.navigate"
                )
            }
            
            "back" -> {
                driver.goBack()
                ActResult(
                    success = true,
                    message = "Navigated back in browser history",
                    action = "navigation.back"
                )
            }
            
            "forward" -> {
                driver.goForward()
                ActResult(
                    success = true,
                    message = "Navigated forward in browser history",
                    action = "navigation.forward"
                )
            }
            
            "refresh" -> {
                driver.reload()
                ActResult(
                    success = true,
                    message = "Page refreshed",
                    action = "navigation.refresh"
                )
            }
            
            else -> ActResult(
                success = false,
                message = "Unknown navigation action: $action",
                action = "navigation.$action"
            )
        }
    }

    override fun validate(context: SkillContext): Boolean {
        val action = context.parameters["action"] as? String ?: return false
        
        return when (action.lowercase()) {
            "navigate" -> context.parameters.containsKey("url")
            "back", "forward", "refresh" -> true
            else -> false
        }
    }
}
