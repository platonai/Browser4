---
name: data-validation
description: Validates data against common and custom rules (required fields, formats, ranges). Use when checking data quality, input validation, or enforcing schemas and constraints.
metadata:
  displayName: Data Validation
  version: "1.0.0"
  author: Browser4
  tags: "validation, data, quality"
  dependencies: ""
---

# Data Validation Skill

## Description

Validate data against specified rules. This skill provides a flexible validation framework supporting common validation patterns like email format, required fields, and custom rules.

## Dependencies

None

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| data | Map<String, Any> | Yes | - | The data to validate |
| rules | List<String> | Yes | - | List of validation rules to apply |

## Return Value

Returns a `SkillResult` with the following data structure on success:

```json
{
  "validationResults": {
    "rule1": true,
    "rule2": true
  }
}
```

On failure, returns:

```json
{
  "validationResults": {
    "rule1": true,
    "rule2": false
  },
  "errors": ["error message 1", "error message 2"]
}
```

## Supported Validation Rules

| Rule | Description | Example |
|------|-------------|---------|
| email | Validates email format | "user@example.com" |
| required | Checks all fields are non-null and non-blank | All values must be present |

## Usage Examples

### Email Validation

```kotlin
val result = registry.execute(
    skillId = "data-validation",
    context = context,
    params = mapOf(
        "data" to mapOf("email" to "test@example.com"),
        "rules" to listOf("email")
    )
)
```

### Multiple Rules Validation

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

## Error Handling

The skill returns a failure result in the following cases:
- Missing required parameter `data`
- Missing required parameter `rules`
- Unknown validation rule specified
- Validation rule fails for the provided data

## Implementation Notes

- Rules are applied in the order specified in the `rules` list
- All rules are executed even if some fail (to provide complete feedback)
- Custom validation rules can be added by extending the skill
- Validation is performed synchronously
- Thread-safe execution

## Extending with Custom Rules

To add custom validation rules, extend the skill and override the execute method to handle additional rule types:

```kotlin
class ExtendedDataValidationSkill : DataValidationSkill() {
    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        // Handle custom rules first
        val rules = params["rules"] as? List<String> ?: emptyList()

        if (rules.contains("custom-rule")) {
            // Implement custom validation
        }

        // Delegate to parent for standard rules
        return super.execute(context, params)
    }
}
```

## Best Practices

- Always provide clear error messages
- Validate input data before processing
- Use multiple validation rules for comprehensive checks
- Combine with other skills in a pipeline for data processing workflows
- Log validation failures for monitoring and debugging

## See Also

- [Web Scraping Skill](../web-scraping/SKILL.md)
- [Form Filling Skill](../form-filling/SKILL.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
