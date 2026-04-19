#!/usr/bin/env kotlin

/**
 * Example script demonstrating form filling usage.
 * 
 * This script shows how to:
 * - Register skills with dependencies
 * - Fill web forms
 * - Handle form submission
 * - Use shared context for multi-step forms
 */

import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.agentic.skills.examples.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Form Filling Skill Example ===\n")
    
    // Initialize registry and context
    val registry = SkillRegistry.instance
    val context = SkillContext(
        sessionId = "form-example-${System.currentTimeMillis()}",
        config = mapOf("timeout" to 30000)
    )
    
    // Load skills with dependencies
    val loader = SkillLoader(registry)
    val results = loader.loadAll(
        listOf(
            WebScrapingSkill(),  // Dependency
            FormFillingSkill()   // Requires web-scraping
        ),
        context
    )
    
    results.forEach { (skillId, success) ->
        if (success) {
            println("✓ Loaded skill: $skillId")
        } else {
            println("✗ Failed to load: $skillId")
        }
    }
    
    // Example 1: Basic form filling
    println("\n--- Example 1: Basic Form Filling ---")
    val result1 = registry.execute(
        skillId = "form-filling",
        context = context,
        params = mapOf(
            "url" to "https://example.com/contact",
            "formData" to mapOf(
                "name" to "John Doe",
                "email" to "john@example.com",
                "message" to "Hello from Browser4!"
            )
        )
    )
    
    if (result1.success) {
        println("✓ Success: ${result1.message}")
        val data = result1.data as? Map<*, *>
        println("  Filled fields: ${data?.get("filledFields")}")
    } else {
        println("✗ Failed: ${result1.message}")
    }
    
    // Example 2: Form filling with submission
    println("\n--- Example 2: Form with Submission ---")
    val result2 = registry.execute(
        skillId = "form-filling",
        context = context,
        params = mapOf(
            "url" to "https://example.com/signup",
            "formData" to mapOf(
                "username" to "johndoe",
                "email" to "john@example.com",
                "password" to "SecurePass123!"
            ),
            "submit" to true
        )
    )
    
    if (result2.success) {
        println("✓ Success: ${result2.message}")
        val data = result2.data as? Map<*, *>
        println("  Submitted: ${data?.get("submitted")}")
    } else {
        println("✗ Failed: ${result2.message}")
    }
    
    // Example 3: Multi-step form
    println("\n--- Example 3: Multi-Step Form ---")
    
    // Step 1
    val step1Result = registry.execute(
        skillId = "form-filling",
        context = context,
        params = mapOf(
            "url" to "https://example.com/signup/step1",
            "formData" to mapOf("email" to "user@example.com")
        )
    )
    
    if (step1Result.success) {
        println("✓ Step 1 complete")
        context.setResource("step1_completed", true)
        
        // Step 2
        val step2Result = registry.execute(
            skillId = "form-filling",
            context = context,
            params = mapOf(
                "url" to "https://example.com/signup/step2",
                "formData" to mapOf(
                    "name" to "John Doe",
                    "phone" to "555-0123"
                )
            )
        )
        
        if (step2Result.success) {
            println("✓ Step 2 complete")
            println("✓ Multi-step form completed successfully")
        }
    }
    
    // Cleanup
    loader.unloadAll(listOf("form-filling", "web-scraping"), context)
    println("\n✓ Cleanup complete")
}
