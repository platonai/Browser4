#!/usr/bin/env kotlin

/**
 * Example script demonstrating basic web scraping usage.
 * 
 * This script shows how to:
 * - Initialize the skill framework
 * - Register the web scraping skill
 * - Execute scraping operations
 * - Handle results
 */

import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.agentic.skills.examples.WebScrapingSkill
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== Web Scraping Skill Example ===\n")
    
    // Initialize registry and context
    val registry = SkillRegistry.instance
    val context = SkillContext(
        sessionId = "example-session-${System.currentTimeMillis()}",
        config = mapOf(
            "timeout" to 30000,
            "userAgent" to "Browser4-Skills/1.0"
        )
    )
    
    // Register the skill
    val skill = WebScrapingSkill()
    registry.register(skill, context)
    println("✓ Registered skill: ${skill.metadata.name} v${skill.metadata.version}")
    
    // Example 1: Basic text extraction
    println("\n--- Example 1: Basic Text Extraction ---")
    val result1 = registry.execute(
        skillId = "web-scraping",
        context = context,
        params = mapOf(
            "url" to "https://example.com",
            "selector" to ".content"
        )
    )
    
    if (result1.success) {
        println("✓ Success: ${result1.message}")
        println("  Data: ${result1.data}")
    } else {
        println("✗ Failed: ${result1.message}")
    }
    
    // Example 2: Extract multiple attributes
    println("\n--- Example 2: Multiple Attributes ---")
    val result2 = registry.execute(
        skillId = "web-scraping",
        context = context,
        params = mapOf(
            "url" to "https://example.com/products",
            "selector" to "a.product-link",
            "attributes" to listOf("text", "href", "data-price")
        )
    )
    
    if (result2.success) {
        println("✓ Success: ${result2.message}")
        println("  Data: ${result2.data}")
    } else {
        println("✗ Failed: ${result2.message}")
    }
    
    // Example 3: Error handling - invalid URL
    println("\n--- Example 3: Error Handling ---")
    val result3 = registry.execute(
        skillId = "web-scraping",
        context = context,
        params = mapOf(
            "url" to "ftp://invalid-protocol.com",
            "selector" to ".content"
        )
    )
    
    if (result3.success) {
        println("✓ Success: ${result3.message}")
    } else {
        println("✗ Expected failure: ${result3.message}")
    }
    
    // Cleanup
    registry.unregister("web-scraping", context)
    println("\n✓ Cleanup complete")
}
