# Data Validation Skill - Developer Guide

## Overview

This guide provides detailed information for developers working with or extending the Data Validation skill.

## Architecture

The Data Validation skill provides a flexible framework for validating data against predefined rules. It's designed to be extensible and composable with other skills.

## Built-in Validation Rules

### Email Validation

Validates email addresses using regex pattern matching:

```kotlin
Pattern: ^[A-Za-z0-9+_.-]+@(.+)$

Examples:
✓ user@example.com
✓ john.doe@company.co.uk
✓ test+tag@gmail.com
✗ invalid@
✗ @example.com
✗ user@
```

### Required Fields Validation

Ensures all fields in the data map are non-null and non-blank:

```kotlin
Examples:
✓ { "name": "John", "age": "25" }
✗ { "name": "", "age": "25" }
✗ { "name": null, "age": "25" }
```

## Adding Custom Rules

Extend the skill to add custom validation rules:

```kotlin
class CustomDataValidationSkill : DataValidationSkill() {
    override suspend fun execute(
        context: SkillContext, 
        params: Map<String, Any>
    ): SkillResult {
        @Suppress("UNCHECKED_CAST")
        val data = params["data"] as? Map<String, Any>
            ?: return SkillResult.failure("Missing required parameter: data")

        @Suppress("UNCHECKED_CAST")
        val rules = params["rules"] as? List<String>
            ?: return SkillResult.failure("Missing required parameter: rules")

        val validationResults = mutableMapOf<String, Boolean>()
        val errors = mutableListOf<String>()

        for (rule in rules) {
            when (rule) {
                // Custom phone validation
                "phone" -> {
                    val phone = data["phone"] as? String
                    val isValid = phone?.matches(
                        Regex("^\\+?[1-9]\\d{1,14}$")
                    ) ?: false
                    validationResults[rule] = isValid
                    if (!isValid) errors.add("Invalid phone format")
                }
                
                // Custom URL validation
                "url" -> {
                    val url = data["url"] as? String
                    val isValid = url?.matches(
                        Regex("^https?://.*")
                    ) ?: false
                    validationResults[rule] = isValid
                    if (!isValid) errors.add("Invalid URL format")
                }
                
                // Custom age range validation
                "age_range" -> {
                    val age = data["age"]?.toString()?.toIntOrNull()
                    val isValid = age != null && age in 18..120
                    validationResults[rule] = isValid
                    if (!isValid) errors.add("Age must be between 18 and 120")
                }
                
                // Delegate to parent for standard rules
                else -> {
                    return super.execute(context, params)
                }
            }
        }

        val allValid = errors.isEmpty()

        return if (allValid) {
            SkillResult.success(
                data = validationResults,
                message = "All validation rules passed"
            )
        } else {
            SkillResult.failure(
                message = "Validation failed: ${errors.joinToString(", ")}",
                metadata = mapOf(
                    "validationResults" to validationResults,
                    "errors" to errors
                )
            )
        }
    }
}
```

## Validation Patterns

### Simple Validation

```kotlin
val result = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to mapOf("email" to "user@example.com"),
        "rules" to listOf("email")
    )
)
```

### Multiple Rules

```kotlin
val result = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to mapOf(
            "email" to "user@example.com",
            "name" to "John Doe",
            "age" to "25"
        ),
        "rules" to listOf("email", "required")
    )
)
```

### Conditional Validation

```kotlin
fun validateConditionally(
    data: Map<String, Any>,
    conditions: Map<String, List<String>>
): SkillResult {
    for ((field, rules) in conditions) {
        if (data.containsKey(field)) {
            val result = registry.execute(
                skillId = "data-validation",
                context = context,
                params = mapOf(
                    "data" to mapOf(field to data[field]),
                    "rules" to rules
                )
            )
            if (!result.success) return result
        }
    }
    return SkillResult.success()
}
```

## Integration with Other Skills

### Pre-submission Validation

```kotlin
// Validate before form submission
val validationResult = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to formData,
        "rules" to listOf("email", "required")
    )
)

if (validationResult.success) {
    // Proceed with form filling
    registry.execute(
        skillId = "form-filling",
        context = context,
        params = mapOf(
            "url" to url,
            "formData" to formData,
            "submit" to true
        )
    )
}
```

### Pipeline Validation

```kotlin
// Create validation pipeline
val composer = SkillComposer(registry)
val pipeline = composer.sequential(
    "validated-submission",
    listOf("data-validation", "form-filling")
)

registry.register(pipeline, context)
```

## Testing

### Unit Tests

```kotlin
@Test
fun testEmailValidation() = runBlocking {
    val skill = DataValidationSkill()
    val context = SkillContext(sessionId = "test")
    
    val result = skill.execute(
        context,
        mapOf(
            "data" to mapOf("email" to "valid@example.com"),
            "rules" to listOf("email")
        )
    )
    
    assertTrue(result.success)
}

@Test
fun testInvalidEmail() = runBlocking {
    val skill = DataValidationSkill()
    val context = SkillContext(sessionId = "test")
    
    val result = skill.execute(
        context,
        mapOf(
            "data" to mapOf("email" to "invalid"),
            "rules" to listOf("email")
        )
    )
    
    assertFalse(result.success)
    assertTrue(result.message?.contains("Invalid email") ?: false)
}
```

### Test Data Builders

```kotlin
object TestDataBuilder {
    fun validEmail() = mapOf("email" to "test@example.com")
    fun invalidEmail() = mapOf("email" to "not-an-email")
    
    fun requiredFields() = mapOf(
        "name" to "John Doe",
        "email" to "john@example.com",
        "phone" to "+1234567890"
    )
    
    fun incompleteFields() = mapOf(
        "name" to "",
        "email" to "john@example.com"
    )
}
```

## Best Practices

1. **Fail Fast**: Return early on validation failures
2. **Clear Messages**: Provide specific error messages
3. **Composability**: Use validation in pipelines
4. **Extensibility**: Add custom rules as needed
5. **Testing**: Write tests for all validation rules

## Performance Considerations

- Validation is synchronous and fast
- Rules execute in order specified
- All rules run even if some fail (complete feedback)
- Consider caching validation results for repeated checks

## Related Resources

- [Form Filling Skill](../form-filling/SKILL.md)
- [Web Scraping Skill](../web-scraping/SKILL.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
