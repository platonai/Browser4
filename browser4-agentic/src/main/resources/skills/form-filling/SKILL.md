---
name: form-filling
description: Automatically fills web forms using provided field data and can optionally submit the form. Use when automating form input, testing forms, or submitting structured data to websites.
metadata:
  displayName: Form Filling
  version: "1.0.0"
  author: Browser4
  tags: "forms, automation, input"
  dependencies: "web-scraping"
---

# Form Filling Skill

## Description

Automatically fill web forms with provided data. This skill handles various form field types and can optionally submit the form after filling.

## Dependencies

- `web-scraping` - Required for detecting form fields and structure

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| url | String | Yes | - | The URL of the page containing the form |
| formData | Map<String, String> | Yes | - | Key-value pairs representing field names and values |
| submit | Boolean | No | false | Whether to submit the form after filling |

## Return Value

Returns a `SkillResult` with the following data structure:

```json
{
  "url": "string",
  "filledFields": ["field1", "field2"],
  "submitted": boolean,
  "message": "status message"
}
```

## Usage Examples

### Basic Form Filling

```kotlin
val result = registry.execute(
    skillId = "form-filling",
    context = context,
    params = mapOf(
        "url" to "https://example.com/contact",
        "formData" to mapOf(
            "name" to "John Doe",
            "email" to "john@example.com",
            "message" to "Hello!"
        )
    )
)
```

### Form Filling with Submission

```kotlin
val result = registry.execute(
    skillId = "form-filling",
    context = context,
    params = mapOf(
        "url" to "https://example.com/signup",
        "formData" to mapOf(
            "username" to "johndoe",
            "password" to "securepass123",
            "email" to "john@example.com"
        ),
        "submit" to true
    )
)
```

## Tool Call Specification

```kotlin
ToolSpec(
    domain = "skill.form",
    method = "fill",
    arguments = [
        "url: String",
        "formData: Map<String, String>",
        "submit: Boolean = false"
    ],
    returnType = "SkillResult",
    description = "Fill a web form with the provided data"
)
```

## Error Handling

The skill returns a failure result in the following cases:
- Missing required parameter `url`
- Missing required parameter `formData`
- Empty `formData` map
- Form fields not found on the page

## Lifecycle Hooks

### onBeforeExecute
Validates that formData is not empty before execution.

### validate
Checks if the required dependency skill (web-scraping) is available in the registry.

## Implementation Notes

- Supports various input types: text, email, password, textarea, select, checkbox, radio
- Automatically handles form field detection and mapping
- Respects CSRF tokens and hidden fields
- Can handle multi-page forms through shared context
- Thread-safe execution

## Security Considerations

- Never log sensitive form data (passwords, credit cards, etc.)
- Validate form URLs to prevent CSRF attacks
- Use HTTPS URLs for sensitive forms
- Clear sensitive data from memory after use

## See Also

- [Web Scraping Skill](../web-scraping/SKILL.md)
- [Data Validation Skill](../data-validation/SKILL.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
