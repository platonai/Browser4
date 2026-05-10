#!/usr/bin/env kotlin

/**
 * Example script demonstrating data validation usage.
 * 
 * This script shows how to:
 * - Validate data using built-in rules
 * - Handle validation failures
 * - Combine validation with other skills
 * - Use validation in pipelines
 */

import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.agentic.skills.examples.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Data Validation Skill Example ===\n")
    
    // Initialize registry and context
    val registry = SkillRegistry.instance
    val context = SkillContext(
        sessionId = "validation-example-${System.currentTimeMillis()}"
    )
    
    // Register the skill
    val skill = DataValidationSkill()
    registry.register(skill, context)
    println("✓ Registered skill: ${skill.metadata.name} v${skill.metadata.version}")
    
    // Example 1: Email validation
    println("\n--- Example 1: Email Validation ---")
    val result1 = registry.execute(
        skillId = "data-validation",
        context = context,
        params = mapOf(
            "data" to mapOf("email" to "user@example.com"),
            "rules" to listOf("email")
        )
    )
    
    if (result1.success) {
        println("✓ Valid email format")
        println("  Results: ${result1.data}")
    } else {
        println("✗ Invalid: ${result1.message}")
    }
    
    // Example 2: Invalid email
    println("\n--- Example 2: Invalid Email ---")
    val result2 = registry.execute(
        skillId = "data-validation",
        context = context,
        params = mapOf(
            "data" to mapOf("email" to "not-an-email"),
            "rules" to listOf("email")
        )
    )
    
    if (result2.success) {
        println("✓ Valid")
    } else {
        println("✗ Expected failure: ${result2.message}")
        val metadata = result2.metadata
        println("  Errors: ${metadata["errors"]}")
    }
    
    // Example 3: Multiple rules
    println("\n--- Example 3: Multiple Rules ---")
    val result3 = registry.execute(
        skillId = "data-validation",
        context = context,
        params = mapOf(
            "data" to mapOf(
                "email" to "john@example.com",
                "name" to "John Doe",
                "age" to "25"
            ),
            "rules" to listOf("email", "required")
        )
    )
    
    if (result3.success) {
        println("✓ All validation rules passed")
        println("  Results: ${result3.data}")
    } else {
        println("✗ Failed: ${result3.message}")
    }
    
    // Example 4: Integration with form filling
    println("\n--- Example 4: Validated Form Submission ---")
    
    // Register form filling skill
    val loader = SkillLoader(registry)
    loader.loadAll(
        listOf(WebScrapingSkill(), FormFillingSkill()),
        context
    )
    
    val formData = mapOf(
        "email" to "user@example.com",
        "name" to "Jane Doe",
        "phone" to "555-1234"
    )
    
    // Validate first
    val validationResult = registry.execute(
        skillId = "data-validation",
        context = context,
        params = mapOf(
            "data" to formData,
            "rules" to listOf("email", "required")
        )
    )
    
    if (validationResult.success) {
        println("✓ Data validation passed")
        
        // Proceed with form filling
        val formResult = registry.execute(
            skillId = "form-filling",
            context = context,
            params = mapOf(
                "url" to "https://example.com/submit",
                "formData" to formData,
                "submit" to true
            )
        )
        
        if (formResult.success) {
            println("✓ Form submitted successfully")
        } else {
            println("✗ Form submission failed: ${formResult.message}")
        }
    } else {
        println("✗ Validation failed, skipping form submission")
        println("  Errors: ${validationResult.metadata["errors"]}")
    }
    
    // Example 5: Create validation pipeline
    println("\n--- Example 5: Validation Pipeline ---")
    val composer = SkillComposer(registry)
    val pipeline = composer.sequential(
        "validated-submission",
        listOf("data-validation", "form-filling")
    )
    
    println("✓ Created validation pipeline")
    
    // Cleanup
    loader.unloadAll(listOf("form-filling", "web-scraping"), context)
    registry.unregister("data-validation", context)
    println("\n✓ Cleanup complete")
}
