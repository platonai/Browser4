# X-SQL Skill — Developer Guide

## Overview

The X-SQL skill wraps the Browser4 X-SQL query engine as an agent-callable skill. It accepts X-SQL queries (or high-level parameters that auto-generate queries) and returns structured tabular results.

This guide covers the internal architecture, extension points, and integration patterns for developers working with or extending the X-SQL skill.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      Agent / Client                       │
│  Calls skill.execute() with query or url+selector+fields  │
└─────────────────────┬────────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────────┐
│                    X-SQL Skill (SKILL.md)                  │
│  - Validates parameters                                   │
│  - Auto-generates query from convenience params           │
│  - Delegates to XSqlQueryEngine                           │
│  - Formats results as SkillResult                         │
└─────────────────────┬────────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────────┐
│                XSqlQueryEngine / H2Session                 │
│  - Parses X-SQL                                           │
│  - Resolves UDFs (DOM, STR, LLM, etc.)                    │
│  - Manages page loading via PulsarSession                 │
│  - Executes query and returns ResultSet                   │
└─────────────────────┬────────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────────┐
│                  PulsarSession / Jsoup                     │
│  - Fetches web pages (cache or network)                   │
│  - Parses HTML into DOM                                   │
│  - Evaluates CSS selectors                                │
└──────────────────────────────────────────────────────────┘
```

## Components

### 1. SkillMetadata

```kotlin
override val metadata = SkillMetadata(
    id = "x-sql",
    name = "X-SQL Web Query",
    version = "1.0.0",
    author = "Browser4",
    tags = listOf("scraping", "extraction", "web", "sql", "dom"),
    dependencies = emptyList()
)
```

### 2. ToolCallSpecs

The skill exposes tool calls that agents can invoke:

```kotlin
override val toolSpecs = listOf(
    ToolSpec(
        domain = "skill.xsql",
        method = "query",
        arguments = ["query: String"],
        returnType = "SkillResult",
        description = "Execute an X-SQL query against web pages"
    ),
    ToolSpec(
        domain = "skill.xsql",
        method = "extract",
        arguments = [
            "url: String",
            "selector: String",
            "fields: Map<String, String>"
        ],
        returnType = "SkillResult",
        description = "Quick extraction: auto-generates and executes an X-SQL query"
    )
)
```

### 3. Query Auto-Generation

When `url`, `selector`, and `fields` are provided instead of a raw `query`, the skill auto-generates the X-SQL:

```kotlin
fun generateQuery(url: String, selector: String, fields: Map<String, String>): String {
    val selectClauses = fields.entries.joinToString(",\n  ") { (name, css) ->
        when {
            css.contains("img") || name.lowercase().contains("img") ->
                "dom_first_img(dom, '$css') AS $name"
            css == "a" || name.lowercase().contains("link") || name.lowercase().contains("href") ->
                "dom_first_href(dom, '$css') AS $name"
            css.contains("@") ->
                "dom_first_attr(dom, '${css.replace(Regex("^.*@"), "")}', '${css.replace(Regex("@.*$"), "")}') AS $name"
            else ->
                "dom_first_text(dom, '$css') AS $name"
        }
    }

    return """
        SELECT
          $selectClauses
        FROM load_and_select('$url', '$selector')
    """.trimIndent()
}
```

## Query Analysis & Validation

### Selector Validation

Before executing, validate that selectors use supported syntax:

```kotlin
fun validateSelector(css: String): Boolean {
    // Jsoup selectors can't contain JS expressions except via :expr()
    // Check for balanced brackets, valid pseudo-classes, etc.
    val dangerousSelectors = listOf("<script", "javascript:", "onerror=")
    return dangerousSelectors.none { css.contains(it, ignoreCase = true) }
}
```

### Result Size Estimation

For large crawls, estimate the result size before fetching:

```kotlin
fun estimateRowCount(url: String, selector: String): Int? {
    // Quick pre-flight: load just the page metadata, count matching elements
    // Returns null if estimation fails
}
```

## Extending the Skill

### Adding a Custom UDF Namespace

Register custom functions that agents can use in X-SQL queries:

```kotlin
@UDFGroup(namespace = "CUSTOM")
object CustomFunctions {
    @UDFunction(description = "Example custom function")
    @JvmStatic
    fun myTransform(text: String): String {
        return text.reversed()
    }
}

// Register with H2Session
session.registerUdfClass(CustomFunctions::class)
```

### Adding a Post-Processing Hook

Transform results before returning to the agent:

```kotlin
class XSqlWithPostProcessingSkill : XSqlSkill() {
    override suspend fun execute(
        context: SkillContext,
        params: Map<String, Any>
    ): SkillResult {
        val result = super.execute(context, params)

        if (result.success && params["postProcess"] == true) {
            val data = result.data as? Map<String, Any> ?: return result
            val rows = data["rows"] as? List<Map<String, Any>> ?: return result

            // Example: deduplicate by a key column
            val dedupKey = params["dedupKey"] as? String ?: return result
            val deduped = rows.distinctBy { it[dedupKey] }

            return SkillResult.success(
                data = data + ("rows" to deduped) + ("rowCount" to deduped.size)
            )
        }

        return result
    }
}
```

### Composing with Other Skills

Chain X-SQL with validation or form-filling:

```kotlin
// Scrape → Validate → Fill
class ScrapeValidateFillPipeline(
    private val registry: SkillRegistry
) {
    suspend fun execute(
        context: SkillContext,
        scrapeUrl: String,
        formUrl: String
    ): SkillResult {
        // Step 1: Scrape data with X-SQL
        val scrapeResult = registry.execute(
            "x-sql", context,
            mapOf(
                "url" to scrapeUrl,
                "selector" to ".data-row",
                "fields" to mapOf("name" to ".name", "email" to ".email")
            )
        )
        if (!scrapeResult.success) return scrapeResult

        // Step 2: Validate the extracted data
        val rows = (scrapeResult.data as Map<*, *>)["rows"] as List<Map<String, String>>
        val validationResult = registry.execute(
            "data-validation", context,
            mapOf(
                "data" to rows.first(),
                "rules" to listOf("email", "required")
            )
        )
        if (!validationResult.success) return validationResult

        // Step 3: Fill a form with the validated data
        return registry.execute(
            "form-filling", context,
            mapOf(
                "url" to formUrl,
                "formData" to rows.first(),
                "submit" to true
            )
        )
    }
}
```

## Performance Tuning

### Caching Strategy

X-SQL has multiple cache layers:

1. **H2 page cache** — pages stored in the H2 database; controlled by `-i` (interval) URL option
2. **PulsarSession cache** — in-memory page cache within a session
3. **HTTP cache** — respects HTTP `Cache-Control` and `ETag` headers

```sql
-- Cache for 1 day, no JS rendering, max 3 parallel fetches
FROM load_and_select('https://example.com/products -i 1d -njr 3', '.product');
```

### Batching

For large crawls, use `load_out_pages` with `limit` to control concurrency:

```sql
-- Load portal, follow up to 100 links, normalize URLs to deduplicate
FROM load_out_pages('https://example.com/sitemap', 'a', 1, 100, true);
```

### Row Limiting

Always use `offset` and `limit` to control row counts during development:

```sql
FROM load_and_select('https://example.com/products', '.product', 1, 5);
```

## Testing

### Unit Testing a Query

```kotlin
@Test
fun testSimpleExtraction() = runBlocking {
    val skill = XSqlSkill()
    val context = SkillContext(sessionId = "test")

    val result = skill.execute(context, mapOf(
        "query" to """
            SELECT dom_first_text(dom, 'title') AS title
            FROM load_and_select('https://example.com', 'body')
        """.trimIndent()
    ))

    assertTrue(result.success)
    val data = result.data as Map<*, *>
    val rows = data["rows"] as List<*>
    assertTrue(rows.isNotEmpty())
}
```

### Testing Query Generation

```kotlin
@Test
fun testAutoGenerateQuery() {
    val skill = XSqlSkill()
    val query = skill.generateQuery(
        url = "https://example.com/products",
        selector = ".product-card",
        fields = mapOf(
            "title" to "h2.name",
            "price" to ".price",
            "image" to "img.thumb"
        )
    )

    assertTrue(query.contains("dom_first_text(dom, 'h2.name') AS title"))
    assertTrue(query.contains("dom_first_text(dom, '.price') AS price"))
    assertTrue(query.contains("dom_first_img(dom, 'img.thumb') AS image"))
    assertTrue(query.contains("load_and_select('https://example.com/products', '.product-card')"))
}
```

### Integration Testing

```kotlin
@Test
fun testSkillPipeline() = runBlocking {
    val registry = SkillRegistry.instance
    val context = SkillContext(sessionId = "integration-test")

    registry.register(XSqlSkill(), context)
    registry.register(DataValidationSkill(), context)

    // Scrape then validate
    val scrapeResult = registry.execute("x-sql", context, mapOf(
        "url" to "https://example.com/contact",
        "selector" to ".person",
        "fields" to mapOf("name" to ".name", "email" to ".email")
    ))

    assertTrue(scrapeResult.success)
}
```

## Troubleshooting

### Common Issues

| Symptom | Likely Cause | Resolution |
|---|---|---|
| Zero rows returned | Row selector doesn't match | Inspect the page HTML; the structure may have changed |
| `NULL` values for a field | Field selector doesn't match within the row | Check if the field is a direct child of the row or nested deeper |
| Page load timeout | Network issue or anti-bot protection | Add `-njr` flag, increase timeout via load options |
| `Function not found` error | Referenced a non-existent UDF | Run `SELECT * FROM xsqlHelp()` to see available functions |
| Out of memory | Limit too high for a large page | Reduce `limit`, or use more specific selectors |
| LLM extraction returns empty | LLM backend not configured | Verify `llm.name` in session configuration |

### Debugging Queries

Break down complex queries step by step:

```sql
-- Step 1: Verify page loads and selector matches
SELECT dom_tag_name(dom), dom_text(dom)
FROM load_and_select('https://example.com/products', '.product', 1, 3);

-- Step 2: Test individual field extraction
SELECT
  dom_first_text(dom, 'h2') AS title,
  dom_first_text(dom, '.price') AS raw_price
FROM load_and_select('https://example.com/products', '.product', 1, 3);

-- Step 3: Add string cleanup
SELECT
  dom_first_text(dom, 'h2') AS title,
  str_first_float(dom_first_text(dom, '.price'), 0.0) AS price
FROM load_and_select('https://example.com/products', '.product', 1, 3);
```

### Inspecting Available Functions

```sql
-- List all registered X-SQL functions
SELECT * FROM xsqlHelp();

-- Filter by namespace
SELECT * FROM xsqlHelp() WHERE NAMESPACE = 'DOM';

-- List all load options
SELECT * FROM loadOptions();
```

## Related Resources

- [X-SQL Complete Function Reference](/docs/x-sql.md)
- [X-SQL Skill Specification](../SKILL.md)
- [Query Templates](../assets/query-templates.md)
- [Skills Framework Documentation](/docs/skills-framework.md)
- [PulsarSession API](https://github.com/apache/pulsar)
- [Jsoup CSS Selector Reference](https://jsoup.org/cookbook/extracting-data/selector-syntax)
