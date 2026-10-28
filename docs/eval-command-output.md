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
| `undefined` (e.g. `var x = 5`, `for` loops, function declarations) | `null` plus an explanation of how to return a value (`--json` keeps JSON `null`) |
| `null` | `null` |
| `""` (empty string) | `""` |
| `Promise` (an async expression without `--await`) | **error**: `The expression returned an unresolved Promise … re-run with --await` |
| `0`, `false`, `"hello"`, `42`, etc. | The value as-is |
| `throw new Error(...)` | Error message surfaced to stderr |

> **Note:** `undefined` and `null` both arrive from CDP without a `value` field, so the
> backend distinguishes them with the CDP `type` (`cdpType == "undefined"`) before the
> text is rendered (`AbstractToolExecutor.jsEvaluationIssue`). An `undefined` result
> carries a plain-text notice — a bare `null` taught the model nothing about why there
> was no data (2026-10-28 run: three `eval`s of a 20-page batch silently lost it).
>
> **Promises are a failure, not data.** Without `--await` the value is an unresolved
> `Promise` object; it used to reach the CLI as `{}`, which read like a result. The
> backend now fails the call and names the fix.

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
