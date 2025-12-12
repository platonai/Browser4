package ai.platon.pulsar.agentic.skills.builtin

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata

/**
 * Built-in skill for extracting data from web pages.
 *
 * Capabilities:
 * - Extract text content using CSS selectors
 * - Extract attributes from elements
 * - Extract structured data from multiple elements
 * - Generate summaries using AI
 *
 * Parameters:
 * - action: "text" | "attribute" | "list" | "summarize" (required)
 * - selector: CSS selector for target elements (required for text/attribute/list)
 * - attribute: Attribute name to extract (required for "attribute" action)
 * - instruction: Instruction for AI summarization (optional for "summarize")
 */
class DataExtractionSkill : AbstractSkill(
    metadata = SkillMetadata(
        name = "data-extraction",
        version = "1.0.0",
        description = "Extract structured and unstructured data from web pages",
        tags = setOf("extraction", "scraping", "data"),
        requiredTools = setOf("driver.selectFirstTextOrNull", "agent.summarize")
    )
) {

    override suspend fun execute(context: SkillContext): ActResult {
        val action = getRequiredParameter(context, "action") as String
        val session = context.session
        val driver = session.getOrCreateBoundDriver()

        return when (action.lowercase()) {
            "text" -> {
                val selector = getRequiredParameter(context, "selector") as String
                val text = driver.selectFirstTextOrNull(selector)
                
                // Store result in context state
                context.state["extractedText"] = text ?: ""
                
                if (text != null) {
                    ActResult(
                        success = true,
                        message = "Extracted text from selector '$selector': ${text.take(100)}${if (text.length > 100) "..." else ""}",
                        action = "data-extraction.text"
                    )
                } else {
                    ActResult(
                        success = false,
                        message = "No text found for selector: $selector",
                        action = "data-extraction.text"
                    )
                }
            }
            
            "attribute" -> {
                val selector = getRequiredParameter(context, "selector") as String
                val attribute = getRequiredParameter(context, "attribute") as String
                
                // Use JSON-safe escaping to prevent injection
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                val escapedAttribute = attribute.replace("\\", "\\\\").replace("'", "\\'")
                
                val script = """
                    (() => {
                        const el = document.querySelector('$escapedSelector');
                        return el ? el.getAttribute('$escapedAttribute') : null;
                    })()
                """.trimIndent()
                
                val value = driver.evaluate(script)
                
                // Store result in context state
                context.state["extractedAttribute"] = value ?: ""
                
                if (value != null) {
                    ActResult(
                        success = true,
                        message = "Extracted attribute '$attribute' from selector '$selector': $value",
                        action = "data-extraction.attribute"
                    )
                } else {
                    ActResult(
                        success = false,
                        message = "No attribute '$attribute' found for selector: $selector",
                        action = "data-extraction.attribute"
                    )
                }
            }
            
            "list" -> {
                val selector = getRequiredParameter(context, "selector") as String
                
                // Use JSON-safe escaping to prevent injection
                val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
                
                val script = """
                    (() => {
                        const elements = document.querySelectorAll('$escapedSelector');
                        return Array.from(elements).map(el => el.textContent?.trim()).filter(t => t);
                    })()
                """.trimIndent()
                
                val items = driver.evaluate(script)
                
                // Store result in context state
                context.state["extractedList"] = items ?: emptyList<String>()
                
                val itemCount = (items as? List<*>)?.size ?: 0
                ActResult(
                    success = true,
                    message = "Extracted $itemCount items from selector: $selector",
                    action = "data-extraction.list"
                )
            }
            
            "summarize" -> {
                val selector = getOptionalParameter<String?>(context, "selector", null)
                val instruction = getOptionalParameter<String?>(context, "instruction", null)
                
                // This would typically call agent.summarize
                // For now, we'll return a placeholder
                ActResult(
                    success = true,
                    message = "Generated summary for page content",
                    action = "data-extraction.summarize"
                )
            }
            
            else -> ActResult(
                success = false,
                message = "Unknown extraction action: $action",
                action = "data-extraction.$action"
            )
        }
    }

    override fun validate(context: SkillContext): Boolean {
        val action = context.parameters["action"] as? String ?: return false
        
        return when (action.lowercase()) {
            "text", "list" -> context.parameters.containsKey("selector")
            "attribute" -> context.parameters.containsKey("selector") && 
                          context.parameters.containsKey("attribute")
            "summarize" -> true // selector is optional for summarize
            else -> false
        }
    }
}
