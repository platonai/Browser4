package ai.platon.pulsar.examples.skills

import ai.platon.pulsar.agentic.context.AgenticContexts
import kotlinx.coroutines.delay

/**
 * Demonstrates the Claude Skills framework in Browser4.
 *
 * This example shows how to:
 * 1. Use built-in skills for navigation, data extraction, and form interaction
 * 2. Discover available skills
 * 3. Compose skills into workflows
 */
suspend fun main() {
    println("=== Claude Skills Framework Demo ===\n")

    // Create an agentic session
    val session = AgenticContexts.createSession(headless = true)
    val skillManager = session.getSkillManager()

    try {
        // 1. Discover available skills
        println("1. Available Skills:")
        val skillNames = skillManager.getSkillNames()
        skillNames.forEach { name ->
            val info = skillManager.getSkillInfo(name)
            println("   - $name (v${info?.metadata?.version}): ${info?.metadata?.description}")
        }
        println()

        // 2. Use Navigation Skill
        println("2. Navigation Skill Demo:")
        println("   Navigating to Hacker News...")
        val navResult = session.executeSkill(
            "navigation",
            mapOf(
                "action" to "navigate",
                "url" to "https://news.ycombinator.com",
                "waitForSelector" to ".itemlist"
            )
        )
        println("   Result: ${navResult.message}")
        delay(2000) // Wait for page to settle
        println()

        // 3. Use Data Extraction Skill
        println("3. Data Extraction Skill Demo:")
        println("   Extracting top story title...")
        val extractResult = session.executeSkill(
            "data-extraction",
            mapOf(
                "action" to "text",
                "selector" to ".titleline > a"
            )
        )
        println("   Result: ${extractResult.message}")
        println()

        // 4. Extract multiple items
        println("4. Extracting Multiple Items:")
        println("   Getting list of story titles...")
        val listResult = session.executeSkill(
            "data-extraction",
            mapOf(
                "action" to "list",
                "selector" to ".titleline > a"
            )
        )
        println("   Result: ${listResult.message}")
        println()

        // 5. Demonstrate skill composition - a workflow
        println("5. Skill Composition Workflow:")
        println("   Navigating to a search page...")
        
        session.executeSkill(
            "navigation",
            mapOf(
                "action" to "navigate",
                "url" to "https://www.google.com"
            )
        )
        delay(2000)
        
        println("   Searching for 'Browser4'...")
        session.executeSkill(
            "form-interaction",
            mapOf(
                "action" to "fill",
                "selector" to "textarea[name='q']",
                "value" to "Browser4"
            )
        )
        delay(1000)
        
        println("   Submitting search...")
        session.executeSkill(
            "form-interaction",
            mapOf(
                "action" to "submit",
                "selector" to "textarea[name='q']"
            )
        )
        delay(3000)
        
        println("   Extracting search results...")
        val searchResults = session.executeSkill(
            "data-extraction",
            mapOf(
                "action" to "text",
                "selector" to "h3"
            )
        )
        println("   Result: ${searchResults.message}")
        println()

        // 6. Get skill statistics
        println("6. Skill Manager Statistics:")
        val stats = skillManager.getStatistics()
        stats.forEach { (key, value) ->
            println("   $key: $value")
        }
        println()

        // 7. Search for skills by query
        println("7. Searching for skills:")
        val formSkills = skillManager.searchSkills("form")
        println("   Skills matching 'form': ${formSkills.map { it.metadata.name }}")
        
        val navigationSkills = skillManager.findSkillsByTag("navigation")
        println("   Skills tagged 'navigation': ${navigationSkills.map { it.metadata.name }}")
        println()

        println("=== Demo Complete ===")

    } catch (e: Exception) {
        println("Error during demo: ${e.message}")
        e.printStackTrace()
    } finally {
        // Cleanup
        AgenticContexts.shutdown()
    }
}
