---
name: web-scraping
description: Extracts data from web pages using browser automation and CSS/JavaScript selectors. Use when you need to scrape titles/text/structured fields from live web pages or when the user mentions scraping, extraction, or selectors.
metadata:
  displayName: Web Scraping
  version: "1.0.0"
  author: Browser4
  tags: "scraping, extraction, web"
  dependencies: ""
---

# Web Scraping Skill

## Description

Extract data from web pages using JavaScript and CSS selectors. This skill uses real browser automation to navigate to pages and extract content using JavaScript execution. It can extract webpage titles using `document.title` and body text using `document.body.textContent`, providing actual content from live web pages.

## Dependencies

None

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| url | String | Yes | - | The URL of the web page to scrape |
| selector | String | Yes | - | CSS selector to target specific elements |
| attributes | List<String> | No | ["text"] | List of attributes to extract (e.g., "text", "href", "src") |

## Return Value

Returns a `SkillResult` with the following data structure:

```json
{
  "url": "string",
  "selector": "string",
  "attributes": ["string"],
  "data": "extracted content"
}
```

## Usage Examples

### Basic Text Extraction

```kotlin
val result = registry.execute(
    skillId = "web-scraping",
    context = context,
    params = mapOf(
        "url" to "https://example.com",
        "selector" to ".content"
    )
)
```

### Extract Multiple Attributes

```kotlin
val result = registry.execute(
    skillId = "web-scraping",
    context = context,
    params = mapOf(
        "url" to "https://example.com/products",
        "selector" to "a.product-link",
        "attributes" to listOf("text", "href")
    )
)
```

## Tool Call Specification

```kotlin
ToolSpec(
    domain = "skill.debug.scraping",
    method = "extract",
    arguments = [
        "url: String",
        "selector: String",
        "attributes: List<String> = listOf('text')"
    ],
    returnType = "Map<String, Any>",
    description = "Extract data from a web page using CSS selectors"
)
```

## Error Handling

The skill returns a failure result in the following cases:
- Missing required parameter `url`
- Missing required parameter `selector`
- Invalid URL format (must start with http:// or https://)

## Lifecycle Hooks

### onLoad
Initializes resources and loads configurations when the skill is registered.

### onBeforeExecute
Validates URL format before execution. Returns false if URL doesn't start with http:// or https://.

### onAfterExecute
Records the timestamp of successful scraping operations in shared resources.

### validate
Validates skill configuration and environment. Always returns true for this skill.

## Implementation Notes

- Uses JavaScript execution via WebDriver.evaluate() to extract real webpage content
- Extracts document title using `document.title`
- Extracts page text using `document.body.textContent`
- Falls back to PulsarSession-based extraction if WebDriver is not directly available
- Returns simulated data only if neither WebDriver nor PulsarSession is available in the skill context
- Supports both synchronous and asynchronous execution patterns
- Thread-safe and can be used in concurrent environments
- Text content is limited to 5000 characters to avoid overwhelming responses

## See Also

- [Form Filling Skill](../form-filling/SKILL.md)
- [Data Validation Skill](../data-validation/SKILL.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
