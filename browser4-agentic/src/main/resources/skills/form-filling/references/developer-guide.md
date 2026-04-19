# Form Filling Skill - Developer Guide

## Overview

This guide provides detailed information for developers working with or extending the Form Filling skill.

## Architecture

The Form Filling skill automates form input across various web forms, handling different field types and submission workflows.

### Components

1. **SkillMetadata** - Defines skill identity, dependencies, and capabilities
2. **ToolCallSpecs** - Exposes form filling functionality to agents
3. **Execute Method** - Core form filling logic
4. **Lifecycle Hooks** - Validation and dependency checking

## Form Field Types

The skill supports various HTML form field types:

### Text Inputs
- `<input type="text">` - Plain text
- `<input type="email">` - Email addresses
- `<input type="password">` - Passwords
- `<input type="tel">` - Phone numbers
- `<input type="url">` - URLs
- `<textarea>` - Multi-line text

### Selection Inputs
- `<select>` - Dropdown lists
- `<input type="radio">` - Radio buttons
- `<input type="checkbox">` - Checkboxes

### Other Inputs
- `<input type="file">` - File uploads
- `<input type="date">` - Date pickers
- `<input type="number">` - Numeric inputs

## Best Practices

### Field Mapping

Map form field names correctly:

```kotlin
val formData = mapOf(
    // Use actual form field 'name' attributes
    "username" to "johndoe",      // <input name="username">
    "email" to "john@example.com", // <input name="email">
    "password" to "secure123"      // <input name="password">
)
```

### Sensitive Data

Handle sensitive data securely:

```kotlin
// DO: Use secure memory handling
val sensitiveData = secureMapOf(
    "password" to password,
    "creditCard" to cardNumber
)

try {
    val result = registry.execute(
        skillId = "form-filling",
        context = context,
        params = mapOf(
            "url" to url,
            "formData" to sensitiveData
        )
    )
} finally {
    // Clear sensitive data
    sensitiveData.clear()
}
```

### Form Validation

Always validate data before submission:

```kotlin
// Use data-validation skill first
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
    val result = registry.execute(
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

## Multi-Step Forms

Handle multi-step forms using shared context:

```kotlin
val context = SkillContext(
    sessionId = "multi-step-form",
    sharedResources = mutableMapOf()
)

// Step 1
val step1Result = registry.execute(
    skillId = "form-filling",
    context = context,
    params = mapOf(
        "url" to "https://example.com/signup/step1",
        "formData" to mapOf("email" to "user@example.com")
    )
)

// Save state in shared context
context.setResource("step1_completed", true)

// Step 2
if (step1Result.success) {
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
}
```

## Error Handling

### Common Errors

1. **Field Not Found**
   ```kotlin
   // Verify field names match actual form
   // Use browser dev tools to inspect form
   ```

2. **Validation Failures**
   ```kotlin
   // Check form validation requirements
   // Match format expectations (email, phone, etc.)
   ```

3. **CSRF Token Issues**
   ```kotlin
   // Skill automatically handles CSRF tokens
   // Ensure cookies are preserved in session
   ```

## Testing

### Mock Form Testing

```kotlin
@Test
fun testFormFilling() = runBlocking {
    val skill = FormFillingSkill()
    val context = SkillContext(sessionId = "test")
    
    val result = skill.execute(
        context,
        mapOf(
            "url" to "https://example.com/form",
            "formData" to mapOf(
                "name" to "Test User",
                "email" to "test@example.com"
            )
        )
    )
    
    assertTrue(result.success)
    val data = result.data as Map<*, *>
    assertEquals(2, (data["filledFields"] as List<*>).size)
}
```

## Security Considerations

### Input Sanitization

Always sanitize user input:

```kotlin
fun sanitizeFormData(data: Map<String, String>): Map<String, String> {
    return data.mapValues { (key, value) ->
        when (key) {
            "email" -> value.trim().lowercase()
            "phone" -> value.replace(Regex("[^0-9+]"), "")
            else -> value.trim()
        }
    }
}
```

### HTTPS Only

Enforce HTTPS for sensitive forms:

```kotlin
override suspend fun onBeforeExecute(
    context: SkillContext,
    params: Map<String, Any>
): Boolean {
    val url = params["url"] as? String ?: return false
    val formData = params["formData"] as? Map<*, *> ?: return false
    
    // Check for sensitive fields
    val hasSensitiveData = formData.keys.any { 
        it.toString() in listOf("password", "creditCard", "ssn")
    }
    
    // Require HTTPS for sensitive data
    if (hasSensitiveData && !url.startsWith("https://")) {
        return false
    }
    
    return true
}
```

## Related Resources

- [Web Scraping Skill](../web-scraping/SKILL.md)
- [Data Validation Skill](../data-validation/SKILL.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
