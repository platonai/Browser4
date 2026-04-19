# Tool Format Comparison

This document compares two formats for describing Agent Tools in the system prompt:
1. **Kotlin-like format** - Current approach using Kotlin function signatures
2. **JSON format** - Structured JSON array with tool definitions

## Tool Specification Size
| Format | Lines | Characters |
|--------|-------|------------|
| Kotlin | 49 | 2282 |
| JSON | 335 | 7802 |

## Full System Prompt Size
| Format | Lines | Characters |
|--------|-------|------------|
| Kotlin | 258 | 8936 |
| JSON | 544 | 14460 |

## Key Differences

### Kotlin Format Advantages
- **Compact**: ~71% smaller than JSON
- **Familiar**: Natural syntax for Kotlin/Java developers
- **Token efficient**: Fewer tokens = lower API costs
- **Human readable**: Easy to scan and understand quickly

Example:
```kotlin
driver.click(selector: String)
driver.fill(selector: String, text: String)
```

### JSON Format Advantages
- **Machine readable**: Standard JSON structure
- **Self-documenting**: Explicit field names
- **Tool calling compatible**: Matches OpenAI function calling format
- **Extensible**: Easy to add metadata

Example:
```json
{
  "domain": "driver",
  "method": "click",
  "parameters": [{"name": "selector", "type": "String"}],
  "returns": "Unit"
}
```

## Generated Files
- `kotlin-format-tools.txt` - Raw Kotlin format tool specifications
- `json-format-tools.json` - Raw JSON format tool specifications
- `system-prompt-kotlin.md` - Full system prompt with Kotlin format
- `system-prompt-json.md` - Full system prompt with JSON format

## Recommendation

For token-efficient LLM interactions, the **Kotlin format** is preferred as it uses
39% fewer characters in the system prompt.

For tool/function calling APIs that require JSON schemas, the **JSON format**
provides better compatibility with OpenAI, Anthropic, and other providers' APIs.
