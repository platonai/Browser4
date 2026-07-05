# Eval Command Output

## Input methods

The `eval` command supports multiple ways to provide JavaScript expressions:

| Flag | Description |
|---|---|
| *positional* | Inline expression: `eval "document.title"` |
| `--file <path>` | Read from file: `eval --file script.js` |
| `--stdin` | Read from stdin: `echo ... | eval --stdin` |
| `--base64` | Base64-encoded expression (avoids shell quoting on Windows): `eval --base64 ZG9jdW1lbnQudGl0bGU=` |

## Return value display

The `eval` command always produces visible output for every JavaScript completion value.
Silent failures (no output, no error) were a known issue before the 4.11.x fix — they are now resolved.

| JavaScript completion value | Printed output |
|---|---|
| `undefined` (e.g. `var x = 5`, `for` loops, function declarations) | `null` |
| `null` | `null` |
| `""` (empty string) | `""` |
| `0`, `false`, `"hello"`, `42`, etc. | The value as-is |
| `throw new Error(...)` | Error message surfaced to stderr |

> **Note:** Both `undefined` and `null` print as `null` because the CDP protocol serialises
> both to a non-existent `value` field. Distinguishing them would require inspecting the
> CDP `type` field, which the current `evaluateValue` API discards.

## Batch mode

The same output rules apply in batch mode. Each `eval` step produces a visible result line.

## Implementation

The fix operates at two layers:

1. **Server** (`MCPToolController.kt`): Uses `TcEvaluate.className` to distinguish
   Kotlin `Unit` (action tools with no meaningful return value — should stay silent)
   from JS `null` (which must produce visible output).

2. **CLI** (`main.rs`): `browser_evaluate` results bypass the empty-string filter
   that other tools use. Null-aware formatting treats `"null"`, `""`, and other
   values distinctly — mirroring the `handle_get` pattern.
