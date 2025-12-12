# Claude Skills Framework

## Overview

Claude Skills are modular, reusable task modules designed to enhance Claude's capabilities in the Browser4 framework. Skills serve as custom expertise packages containing instructions, logic, templates, and resources that can be automatically loaded and executed when relevant tasks arise.

## Key Concepts

### What is a Skill?

A Skill is a self-contained module that encapsulates:
- **Metadata**: Identity, version, description, dependencies, and capabilities
- **Execution Logic**: Implementation of specific browser automation or data processing tasks
- **Validation**: Rules for ensuring the skill can execute in a given context
- **Lifecycle Hooks**: Initialization and cleanup operations

### Benefits

- **Consistency**: Ensure repeatable tasks are performed the same way every time
- **Accuracy**: Encapsulate best practices and domain expertise
- **Automation**: Reduce manual intervention for common tasks
- **Reusability**: Share skills across projects and sessions
- **Maintainability**: Update skill logic in one place rather than across multiple scripts
- **Extensibility**: Easily add new capabilities without modifying core code

## Architecture

The Skills framework consists of four main components:

### 1. Skill Interface

The core abstraction defining what a skill is and how it behaves:

```kotlin
interface Skill {
    val metadata: SkillMetadata
    suspend fun execute(context: SkillContext): ActResult
    fun validate(context: SkillContext): Boolean
    fun initialize()
    fun cleanup()
}
```

### 2. SkillRegistry

Manages the lifecycle of skills and provides discovery mechanisms:

- Register and unregister skills
- Query skills by name, tag, or search
- Track dependencies between skills
- Maintain skill availability status

### 3. SkillLoader

Loads skills from various sources:

- Compiled Kotlin/Java classes from classpath
- Skills from filesystem directories
- Built-in skills shipped with Browser4
- Custom skills from external sources

### 4. SkillExecutor

Executes skills within an `AgenticSession` context:

- Validates dependencies before execution
- Creates execution contexts with parameters
- Handles timeouts and error recovery
- Logs execution metrics and results

## Getting Started

### Using Built-in Skills

Browser4 ships with several built-in skills that are automatically loaded:

```kotlin
import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val session = AgenticContexts.createSession()
    
    // Navigate to a URL using the navigation skill
    val result = session.executeSkill(
        "navigation",
        mapOf(
            "action" to "navigate",
            "url" to "https://example.com",
            "waitForSelector" to "body"
        )
    )
    
    println("Navigation result: ${result.message}")
}
```

### Available Built-in Skills

#### 1. Navigation Skill

Handles web navigation tasks:

```kotlin
// Navigate to a URL
session.executeSkill("navigation", mapOf(
    "action" to "navigate",
    "url" to "https://example.com"
))

// Go back
session.executeSkill("navigation", mapOf(
    "action" to "back"
))

// Refresh page
session.executeSkill("navigation", mapOf(
    "action" to "refresh"
))
```

#### 2. Data Extraction Skill

Extracts data from web pages:

```kotlin
// Extract text from a selector
session.executeSkill("data-extraction", mapOf(
    "action" to "text",
    "selector" to ".product-title"
))

// Extract list of elements
session.executeSkill("data-extraction", mapOf(
    "action" to "list",
    "selector" to ".product-item"
))

// Extract attribute value
session.executeSkill("data-extraction", mapOf(
    "action" to "attribute",
    "selector" to "img.product",
    "attribute" to "src"
))
```

#### 3. Form Interaction Skill

Interacts with web forms:

```kotlin
// Fill an input field
session.executeSkill("form-interaction", mapOf(
    "action" to "fill",
    "selector" to "#username",
    "value" to "myusername"
))

// Select dropdown option
session.executeSkill("form-interaction", mapOf(
    "action" to "select",
    "selector" to "#country",
    "value" to "USA"
))

// Check a checkbox
session.executeSkill("form-interaction", mapOf(
    "action" to "check",
    "selector" to "#agree-terms"
))

// Submit form
session.executeSkill("form-interaction", mapOf(
    "action" to "submit",
    "selector" to "#login-form"
))
```

### Discovering Available Skills

```kotlin
val skillManager = session.getSkillManager()

// Get all skill names
val skillNames = skillManager.getSkillNames()
println("Available skills: $skillNames")

// Find skills by tag
val navigationSkills = skillManager.findSkillsByTag("navigation")
println("Navigation skills: ${navigationSkills.map { it.metadata.name }}")

// Search skills
val formSkills = skillManager.searchSkills("form")
println("Form-related skills: ${formSkills.map { it.metadata.name }}")

// Get skill info
val skillInfo = skillManager.getSkillInfo("navigation")
println("Skill: ${skillInfo?.metadata?.name}")
println("Available: ${skillInfo?.isAvailable}")
println("Missing dependencies: ${skillInfo?.missingDependencies}")
```

## Creating Custom Skills

### Basic Skill Implementation

Create a custom skill by implementing the `Skill` interface or extending `AbstractSkill`:

```kotlin
import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata

class CustomScrapingSkill : AbstractSkill(
    metadata = SkillMetadata(
        name = "custom-scraping",
        version = "1.0.0",
        description = "Custom web scraping logic for specific sites",
        tags = setOf("scraping", "custom", "data"),
        requiredTools = setOf("driver.navigateTo", "driver.selectFirstTextOrNull")
    )
) {
    override suspend fun execute(context: SkillContext): ActResult {
        val url = getRequiredParameter(context, "url") as String
        val session = context.session
        val driver = session.getOrCreateBoundDriver()
        
        // Navigate to URL
        driver.navigateTo(url)
        
        // Wait for content
        driver.waitForSelector(".content")
        
        // Extract data
        val title = driver.selectFirstTextOrNull("h1.title")
        val description = driver.selectFirstTextOrNull(".description")
        
        // Store results in context state
        context.state["title"] = title ?: ""
        context.state["description"] = description ?: ""
        
        return ActResult(
            success = true,
            message = "Successfully scraped data from $url",
            action = "custom-scraping"
        )
    }
    
    override fun validate(context: SkillContext): Boolean {
        return context.parameters.containsKey("url")
    }
}
```

### Registering Custom Skills

```kotlin
// Create skill instance
val customSkill = CustomScrapingSkill()

// Register with the global skill manager
val skillManager = session.getSkillManager()
skillManager.registerSkill(customSkill)

// Or use the convenience API
Skills.register(customSkill)

// Now execute it
val result = session.executeSkill("custom-scraping", mapOf(
    "url" to "https://example.com/product"
))
```

### Skills with Dependencies

Skills can depend on other skills:

```kotlin
class AdvancedAnalysisSkill : AbstractSkill(
    metadata = SkillMetadata(
        name = "advanced-analysis",
        version = "1.0.0",
        description = "Performs advanced analysis using multiple extraction skills",
        dependencies = setOf("data-extraction", "custom-scraping"),
        tags = setOf("analysis", "complex")
    )
) {
    override suspend fun execute(context: SkillContext): ActResult {
        // This skill can safely use data-extraction and custom-scraping
        // because they're declared as dependencies
        
        // Implementation here...
        
        return ActResult(
            success = true,
            message = "Analysis complete",
            action = "advanced-analysis"
        )
    }
}
```

## Best Practices

### 1. Skill Design

- **Single Responsibility**: Each skill should do one thing well
- **Clear Naming**: Use descriptive names that indicate the skill's purpose
- **Comprehensive Metadata**: Provide detailed descriptions and tags
- **Document Parameters**: Clearly document required and optional parameters

### 2. Parameter Handling

- **Validate Early**: Check parameters in the `validate()` method
- **Use Type Safety**: Cast parameters with proper error handling
- **Provide Defaults**: Use `getOptionalParameter()` for non-required inputs
- **Document Types**: Clearly document parameter types in skill description

### 3. Error Handling

- **Graceful Failures**: Return meaningful error messages in `ActResult`
- **Log Appropriately**: Use structured logging for debugging
- **Clean Up Resources**: Implement `cleanup()` for resource management
- **Handle Timeouts**: Design skills to work within timeout constraints

### 4. State Management

- **Use Context State**: Store intermediate results in `context.state`
- **Thread Safety**: Ensure skills are thread-safe and reentrant
- **Stateless Preferred**: Prefer stateless designs when possible
- **Clear State**: Don't rely on state across invocations

### 5. Testing

- **Unit Tests**: Test skill logic in isolation
- **Integration Tests**: Test skills within actual sessions
- **Mock Dependencies**: Use mocks for external dependencies
- **Test Edge Cases**: Cover error conditions and boundary cases

## Advanced Topics

### Skill Composition

Compose complex workflows from simple skills:

```kotlin
suspend fun complexWorkflow(session: AgenticSession) {
    // Navigate
    session.executeSkill("navigation", mapOf(
        "action" to "navigate",
        "url" to "https://example.com/login"
    ))
    
    // Fill form
    session.executeSkill("form-interaction", mapOf(
        "action" to "fill",
        "selector" to "#username",
        "value" to "user"
    ))
    
    session.executeSkill("form-interaction", mapOf(
        "action" to "fill",
        "selector" to "#password",
        "value" to "pass"
    ))
    
    // Submit
    session.executeSkill("form-interaction", mapOf(
        "action" to "submit",
        "selector" to "#login-form"
    ))
    
    // Extract data after login
    val result = session.executeSkill("data-extraction", mapOf(
        "action" to "text",
        "selector" to ".user-dashboard"
    ))
}
```

### Skill Configuration

Configure skill behavior via parameters:

```kotlin
val result = session.executeSkill("navigation", mapOf(
    "action" to "navigate",
    "url" to "https://example.com",
    "timeout" to 60000L,  // Custom timeout
    "waitForSelector" to "main.content",
    "delayMs" to 2000L  // Wait after navigation
))
```

### Skill Lifecycle

Skills have a defined lifecycle:

1. **Loading**: Skill class is loaded and instantiated
2. **Registration**: Skill is registered in the SkillRegistry
3. **Initialization**: `initialize()` is called once
4. **Execution**: `execute()` is called for each invocation
5. **Cleanup**: `cleanup()` is called when unregistered or on shutdown

## API Reference

See the [API Documentation](api-reference.md) for detailed API information.

## Examples

See the [Examples](examples.md) for more comprehensive examples and use cases.

## Troubleshooting

### Skill Not Found

If you get a `SkillNotFoundException`:

1. Check that the skill is registered: `skillManager.hasSkill("skill-name")`
2. Verify the skill name is correct (case-sensitive)
3. Ensure built-in skills are loaded: `skillManager.initialize()`

### Missing Dependencies

If execution fails due to missing dependencies:

1. Check skill info: `skillManager.getSkillInfo("skill-name")`
2. Register missing dependency skills first
3. Verify dependencies are available in your classpath

### Validation Failures

If validation fails:

1. Check required parameters are provided
2. Verify parameter types match expectations
3. Review skill documentation for parameter requirements

### Execution Timeouts

If skills timeout:

1. Increase timeout: `session.executeSkill(name, params, timeout = 10.minutes)`
2. Optimize skill implementation to be faster
3. Break complex skills into smaller sub-skills

## See Also

- [Creating Custom Skills](creating-skills.md)
- [Built-in Skills Reference](builtin-skills.md)
- [API Documentation](api-reference.md)
- [Examples and Use Cases](examples.md)
