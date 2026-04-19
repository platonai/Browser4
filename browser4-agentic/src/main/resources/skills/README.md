# Skills Directory Structure

This directory contains agent skills following the standardized directory structure pattern.

## Directory Structure

Each skill follows this structure:

```
skill-name/
├── SKILL.md          # Required: Skill instructions and metadata
├── scripts/          # Optional: Executable scripts and examples
├── references/       # Optional: Developer documentation
└── assets/           # Optional: Configuration, templates, resources
```

## Available Skills

### [Web Scraping](./web-scraping/SKILL.md)
Extract data from web pages using CSS selectors.

**Key Features:**
- CSS selector-based extraction
- Multiple attribute extraction
- Rate limiting support
- Caching capabilities

### [Form Filling](./form-filling/SKILL.md)
Automatically fill web forms with provided data.

**Key Features:**
- Multiple field type support
- Optional form submission
- Multi-step form handling
- Security validation for sensitive data

### [Data Validation](./data-validation/SKILL.md)
Validate data against specified rules.

**Key Features:**
- Email validation
- Required fields checking
- Extensible rule system
- Comprehensive error reporting

### [HTML to PDF](./html-to-pdf/SKILL.md)
Convert live web pages into PDF documents.

**Key Features:**
- URL-to-PDF conversion
- Playwright-first rendering for dynamic pages
- WeasyPrint fallback for static pages
- Machine-readable JSON output with saved file path

### [Weather](./weather/SKILL.md)
Fetch current weather conditions and a 7-day forecast for a named location.

**Key Features:**
- No API key required
- Current conditions plus 7-day forecast
- Open-Meteo geocoding for free-form place names
- Machine-readable JSON output for agents

## Creating a New Skill

To create a new skill, follow these steps:

### 1. Create Directory Structure

```bash
mkdir -p skills/my-skill/{scripts,references,assets}
```

### 2. Create SKILL.md

Create `skills/my-skill/SKILL.md` with this template:

```markdown
# My Skill Name

## Metadata

- **Skill ID**: `my-skill`
- **Name**: My Skill Name
- **Version**: 1.0.0
- **Author**: Your Name
- **Tags**: `tag1`, `tag2`

## Description

Brief description of what the skill does.

## Dependencies

List any skill dependencies, or "None" if independent.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| param1 | String | Yes | - | Description |
| param2 | Boolean | No | false | Description |

## Return Value

Describe the return value structure.

## Usage Examples

Provide code examples showing how to use the skill.

## Error Handling

Document common errors and failure cases.

## See Also

Link to related skills and documentation.
```

### 3. Add Implementation

Add your Kotlin implementation in the appropriate source directory:

```
src/main/kotlin/ai/platon/pulsar/agentic/skills/examples/MySkill.kt
```

### 4. Add Documentation (Optional)

Create developer guides in `references/`:

```
skills/my-skill/references/developer-guide.md
```

### 5. Add Scripts (Optional)

Create example scripts in `scripts/`:

```
skills/my-skill/scripts/example-usage.kts
```

### 6. Add Assets (Optional)

Add configuration files, templates in `assets/`:

```
skills/my-skill/assets/config.json
skills/my-skill/assets/template.md
```

## Best Practices

### SKILL.md

- Keep the description concise and clear
- Document all parameters with types and defaults
- Provide multiple usage examples
- Include error handling scenarios
- Link to related skills and documentation

### Scripts

- Make scripts executable and self-contained
- Include comments explaining each step
- Show common use cases
- Demonstrate error handling

### References

- Provide detailed technical documentation
- Include architecture diagrams if helpful
- Document extension points
- Add troubleshooting guides

### Assets

- Use JSON for configuration files
- Provide templates for common patterns
- Include example data
- Document asset file formats

## Loading Skills

Skills can be loaded and registered using the SkillRegistry:

```kotlin
val registry = SkillRegistry.instance
val context = SkillContext(sessionId = "my-session")

// Register a skill
val skill = MyCustomSkill()
registry.register(skill, context)

// Execute a skill
val result = registry.execute(
    skillId = "my-skill",
    context = context,
    params = mapOf("param1" to "value1")
)
```

## Skill Discovery

Find skills by tags or author:

```kotlin
// Find all scraping skills
val scrapingSkills = registry.findByTag("scraping")

// Find skills by author
val mySkills = registry.findByAuthor("My Organization")
```

## Skill Dependencies

Skills can depend on other skills:

```kotlin
override val metadata = SkillMetadata(
    id = "my-skill",
    name = "My Skill",
    dependencies = listOf("web-scraping", "data-validation")
)
```

The SkillLoader handles dependency resolution automatically:

```kotlin
val loader = SkillLoader(registry)
val results = loader.loadAll(
    listOf(MySkill(), WebScrapingSkill()),
    context
)
```

## Testing Skills

Write tests for your skills:

```kotlin
@Test
fun testMySkill() = runBlocking {
    val skill = MyCustomSkill()
    val context = SkillContext(sessionId = "test")
    
    val result = skill.execute(
        context,
        mapOf("param1" to "value1")
    )
    
    assertTrue(result.success)
    assertNotNull(result.data)
}
```

## Resources

- [Skills Framework Documentation](/docs/skills-framework.md)
- [Example Skills](../examples/)
- [API Documentation](/docs/api.md)
