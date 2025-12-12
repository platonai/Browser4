package ai.platon.pulsar.agentic.skills.builtin

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata
import kotlinx.coroutines.delay

/**
 * Built-in skill for interacting with web forms.
 *
 * Capabilities:
 * - Fill text inputs
 * - Select dropdown options
 * - Check/uncheck checkboxes
 * - Click radio buttons
 * - Submit forms
 *
 * Parameters:
 * - action: "fill" | "select" | "check" | "uncheck" | "submit" (required)
 * - selector: CSS selector for the target element (required)
 * - value: Value to fill/select (required for fill/select actions)
 * - delayMs: Delay after action in milliseconds (optional, default 500)
 */
class FormInteractionSkill : AbstractSkill(
    metadata = SkillMetadata(
        name = "form-interaction",
        version = "1.0.0",
        description = "Interact with web forms, inputs, and form controls",
        tags = setOf("form", "input", "interaction"),
        requiredTools = setOf("driver.fill", "driver.click", "driver.check", "driver.uncheck")
    )
) {

    override suspend fun execute(context: SkillContext): ActResult {
        val action = getRequiredParameter(context, "action") as String
        val selector = getRequiredParameter(context, "selector") as String
        val delayMs = getOptionalParameter(context, "delayMs", 500L) as Long
        
        val session = context.session
        val driver = session.getOrCreateBoundDriver()

        val result = when (action.lowercase()) {
            "fill" -> {
                val value = getRequiredParameter(context, "value") as String
                driver.fill(selector, value)
                ActResult(
                    success = true,
                    message = "Filled input at selector '$selector' with value",
                    action = "form-interaction.fill"
                )
            }
            
            "select" -> {
                val value = getRequiredParameter(context, "value") as String
                
                // Use JSON-safe escaping to prevent injection
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
                
                val script = """
                    (() => {
                        const select = document.querySelector('$escapedSelector');
                        if (!select) return false;
                        
                        // Try to find option by text or value
                        const options = Array.from(select.options);
                        const option = options.find(o => 
                            o.text === '$escapedValue' || o.value === '$escapedValue'
                        );
                        
                        if (option) {
                            select.value = option.value;
                            select.dispatchEvent(new Event('change', { bubbles: true }));
                            return true;
                        }
                        return false;
                    })()
                """.trimIndent()
                
                val success = driver.evaluate(script) as? Boolean ?: false
                
                ActResult(
                    success = success,
                    message = if (success) 
                        "Selected option '$value' in dropdown at selector '$selector'"
                    else 
                        "Failed to select option '$value' in dropdown at selector '$selector'",
                    action = "form-interaction.select"
                )
            }
            
            "check" -> {
                driver.check(selector)
                ActResult(
                    success = true,
                    message = "Checked checkbox at selector '$selector'",
                    action = "form-interaction.check"
                )
            }
            
            "uncheck" -> {
                driver.uncheck(selector)
                ActResult(
                    success = true,
                    message = "Unchecked checkbox at selector '$selector'",
                    action = "form-interaction.uncheck"
                )
            }
            
            "submit" -> {
                // Use JSON-safe escaping to prevent injection
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                
                val script = """
                    (() => {
                        const form = document.querySelector('$escapedSelector');
                        if (form && form.tagName === 'FORM') {
                            form.submit();
                            return true;
                        }
                        // Otherwise try to click the element
                        const button = document.querySelector('$escapedSelector');
                        if (button) {
                            button.click();
                            return true;
                        }
                        return false;
                    })()
                """.trimIndent()
                
                val success = driver.evaluate(script) as? Boolean ?: false
                
                ActResult(
                    success = success,
                    message = if (success) 
                        "Submitted form at selector '$selector'"
                    else 
                        "Failed to submit form at selector '$selector'",
                    action = "form-interaction.submit"
                )
            }
            
            else -> ActResult(
                success = false,
                message = "Unknown form action: $action",
                action = "form-interaction.$action"
            )
        }

        // Add delay after action to allow page to react
        if (result.success && delayMs > 0) {
            delay(delayMs)
        }

        return result
    }

    override fun validate(context: SkillContext): Boolean {
        val action = context.parameters["action"] as? String ?: return false
        val hasSelector = context.parameters.containsKey("selector")
        
        if (!hasSelector) return false
        
        return when (action.lowercase()) {
            "fill", "select" -> context.parameters.containsKey("value")
            "check", "uncheck", "submit" -> true
            else -> false
        }
    }
}
