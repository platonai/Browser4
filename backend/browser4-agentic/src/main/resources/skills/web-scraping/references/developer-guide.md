# Web Scraping Skill - Developer Guide

## Overview

This guide provides detailed information for developers working with or extending the Web Scraping skill.

## Architecture

The Web Scraping skill is built on top of the Browser4 framework and provides a high-level interface for extracting data from web pages.

### Components

1. **SkillMetadata** - Defines skill identity and capabilities
2. **ToolCallSpecs** - Exposes skill functionality to agents
3. **Execute Method** - Core scraping logic
4. **Lifecycle Hooks** - Initialization and cleanup handlers

## CSS Selectors

The skill uses CSS selectors to target elements on web pages. Here are some common patterns:

### Basic Selectors

- `.className` - Select by class
- `#idName` - Select by ID  
- `tagName` - Select by tag
- `[attribute]` - Select by attribute

### Combinators

- `parent > child` - Direct children
- `ancestor descendant` - All descendants
- `prev + next` - Adjacent sibling
- `prev ~ siblings` - General siblings

### Pseudo-classes

- `:first-child` - First child element
- `:last-child` - Last child element
- `:nth-child(n)` - Nth child element
- `:not(selector)` - Negation

## Best Practices

### Performance

1. **Use Specific Selectors**: More specific selectors are faster
   ```kotlin
   // Good
   "div.product-list > .product-item"
   
   // Less optimal
   ".product-item"
   ```

2. **Minimize Attribute Extraction**: Only extract what you need
   ```kotlin
   // Only extract text
   attributes = listOf("text")
   
   // Extract multiple attributes only when necessary
   attributes = listOf("text", "href", "data-id")
   ```

3. **Batch Operations**: Process multiple pages in parallel using composite skills

### Error Handling

Always handle potential errors gracefully:

```kotlin
val result = registry.execute(
    skillId = "web-scraping",
    context = context,
    params = params
)

if (!result.success) {
    logger.error("Scraping failed: ${result.message}")
    // Handle error
}
```

### Rate Limiting

When scraping multiple pages, implement rate limiting to be respectful:

```kotlin
val urls = listOf("url1", "url2", "url3")
for (url in urls) {
    val result = registry.execute(
        skillId = "web-scraping",
        context = context,
        params = mapOf("url" to url, "selector" to selector)
    )
    delay(1000) // 1 second delay between requests
}
```

## Extending the Skill

You can extend the Web Scraping skill to add custom functionality:

```kotlin
class AdvancedWebScrapingSkill : WebScrapingSkill() {
    override suspend fun execute(
        context: SkillContext, 
        params: Map<String, Any>
    ): SkillResult {
        // Add pre-processing
        val processedParams = preprocessParams(params)
        
        // Call parent implementation
        val result = super.execute(context, processedParams)
        
        // Add post-processing
        return if (result.success) {
            postprocessResult(result)
        } else {
            result
        }
    }
    
    private fun preprocessParams(params: Map<String, Any>): Map<String, Any> {
        // Custom preprocessing logic
        return params
    }
    
    private fun postprocessResult(result: SkillResult): SkillResult {
        // Custom postprocessing logic
        return result
    }
}
```

## Testing

### Unit Testing

```kotlin
@Test
fun testBasicScraping() = runBlocking {
    val skill = WebScrapingSkill()
    val context = SkillContext(sessionId = "test-session")
    
    val result = skill.execute(
        context,
        mapOf(
            "url" to "https://example.com",
            "selector" to ".content"
        )
    )
    
    assertTrue(result.success)
    assertNotNull(result.data)
}
```

### Integration Testing

```kotlin
@Test
fun testWithRegistry() = runBlocking {
    val registry = SkillRegistry.instance
    val context = SkillContext(sessionId = "test-session")
    
    registry.register(WebScrapingSkill(), context)
    
    val result = registry.execute(
        "web-scraping",
        context,
        mapOf(
            "url" to "https://example.com",
            "selector" to ".content"
        )
    )
    
    assertTrue(result.success)
}
```

## Troubleshooting

### Common Issues

1. **Empty Results**
   - Verify the selector matches elements on the page
   - Check if content is loaded dynamically (may need wait time)
   - Ensure proper URL format

2. **Performance Issues**
   - Use more specific selectors
   - Reduce attribute extraction
   - Implement caching for repeated requests

3. **Validation Failures**
   - Ensure URL starts with http:// or https://
   - Check parameter types match expected values

## Related Resources

- [Skills Framework Documentation](/docs/skills-framework.md)
- [CSS Selector Reference](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
- [Browser4 Documentation](/README.md)
