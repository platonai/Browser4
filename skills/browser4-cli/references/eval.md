---
title: "eval — Run JavaScript in the Page"
description: "Reference for the eval command: inline/--file/--stdin/--base64 evaluation, --json, element-scoped --ref evaluation, caveats (console.log, arrow functions)."
tier: procedure
---

# eval — Run JavaScript in the Page

Run a JavaScript expression against the **live DOM** of the current page and print its return value. `eval` sees login state, SPA updates and mutations made by earlier `fill`/`click`/`type` commands — it is the tool for live reads, complex transforms, and verification that needs real DOM state.

## Quick Start

```bash
browser4-cli open "https://example.com"

# Inline expression — prints the return value
browser4-cli eval "document.title"

# Machine-readable output (scalars become quoted strings)
browser4-cli eval --json "document.querySelectorAll('.product-card').length"

# Element-scoped read: the expression MUST be an arrow function
browser4-cli eval "element => element.textContent" --ref e5
```

- The expression runs in the page context; JS exceptions are surfaced as CLI errors.
- Only the **return value** is printed — `console.log` output is discarded.
- Anything beyond a trivial expression belongs in `--file`, `--stdin` or `--base64` (see [shell-quoting.md](shell-quoting.md)).

## When to Use

- **Live reads** after an interaction, when a full `snapshot`/`htmlsnapshot` capture would cost a round-trip.
- **Arbitration** when a capture-based path disagrees about a page fact (stored vs live DOM).
- **Element properties** (text, attributes, styles, computed values) through element-scoped `--ref`.
- **Complex transforms** (`JSON.stringify`, array mapping, joins) that would otherwise need several commands.

Prefer a dedicated command when one exists (`click`, `fill`, `snapshot grep`, `htmlsnapshot query`) — `eval` is the general-purpose escape hatch, not the first choice for interaction or bulk extraction.

## How It Works

The expression is evaluated over CDP against the live page. Input channel and target are independent:

- **Input channel** — inline argument, `--file` (multi-line scripts, no shell quoting), `--stdin` (alias `--js`), or `--base64` (quoting-proof). `--file` also accepts the `@`-prefix convention used across the CLI: `eval --file "@script.js"` behaves like `--sql @query.sql`.
- **Target** — the page by default, or a single element when a ref (`e5`) is passed positionally or via `--ref`. Element-scoped evaluation passes the element as the expression's first argument, so the expression **MUST be an arrow function**; bare `element.property` does **not** work (the element is an argument, not a global).

Return-value semantics:

- `null` for JS null/undefined, `""` for an empty string, otherwise the value itself.
- Objects and arrays are serialized as valid JSON.
- `--json` wraps the result in the CLI's JSON envelope; scalar results become quoted strings, numbers/booleans/null pass through.
- **`console.log()` is not captured.** Scripts written in the natural "compute and log" style print only the value of the last statement (often `null`). Use `return` (or end with the value); the CLI prints a reminder when it detects `console.log` in the expression.

## Patterns

### Verify page state after an interaction (live, capture-free)

```bash
browser4-cli click <submit-ref>
browser4-cli eval "document.querySelector('#result').textContent"   # submission result
browser4-cli eval "document.querySelectorAll('.product-card').length"  # 6
```

### Cross-check facts that extraction paths disagree about

`eval` reads the live DOM directly — use it to arbitrate when `htmlsnapshot` (which may read a cached/stored page) disagrees with `snapshot` about basic page facts:

```bash
browser4-cli eval "document.querySelectorAll('a').length"   # live link count
browser4-cli eval "JSON.stringify([...document.querySelectorAll('a')].map(a => a.getAttribute('href')))"
```

### Multi-line script from a file

```js
// page_info.js — return value is what gets printed
(() => {
  const links = document.querySelectorAll("a").length;
  const images = document.querySelectorAll("img").length;
  const forms = document.querySelectorAll("form").length;
  return { links, images, forms };
})();
```

```bash
browser4-cli eval --file page_info.js --json
# → {"links":3,"images":2,"forms":1}
```

`console.log(...)` inside the file is discarded — only the returned object is printed.

## Flags

| Option | Description |
|--------|-------------|
| `eval "<expression>"` | Inline expression — best for simple reads |
| `--file <path>` | Read the expression from a file (also accepts `@<path>`) — best for multi-line scripts |
| `--stdin` / `--js` | Read the expression from stdin (`--js` is an alias) — best for heredocs and complex quoting |
| `--base64 <b64>` | Quoting-proof inline form (Windows: `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('expr'))`) |
| `--json` | Wrap the result in the CLI's JSON envelope |
| `--ref <ref>` | Evaluate against one element; the expression receives it as its first argument and must be an arrow function |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Printed value is `null` although the script computes something | `console.log` output is not captured; the last statement's value is | End with the value or use `return` |
| `element is not defined` | The expression used `element.property` instead of an arrow function | Write `element => element.property` and pass the ref (`--ref e5` or positionally) |
| Expression mangled by the shell | Quotes stripped by bash/PowerShell before the CLI sees them | Use `--file`, `--stdin`/`--js`, `--base64`, or see [shell-quoting.md](shell-quoting.md) |
| JS exception reported as a CLI error | The expression threw in the page (typo, missing element) | Guard with optional chaining or select the element first |
| Element ref no longer resolves | Refs are ephemeral — they expire after navigation/interaction | Take a fresh `snapshot -i` and use the new ref |

## Windows / Git Bash quoting

- Prefer `--file`, `--stdin`, or `--base64` for anything beyond a trivial expression (see [shell-quoting.md](shell-quoting.md)).
- When invoking through `./b4w.ps1` from Git Bash, arguments are re-quoted by the bash→pwsh boundary; quote each argument individually (`./b4w.ps1 "eval" "--json" "document.title"`) or use `./b4w.sh`.

## See also

- [htmlsnapshot.md](htmlsnapshot.md) — capture-based extraction (stored/cached reads)
- [snapshot.md](snapshot.md) — accessibility tree with element refs for interaction
- [css-selector-bridge.md](css-selector-bridge.md) — bridging refs to CSS selectors
- [shell-quoting.md](shell-quoting.md) — quoting pitfalls for JS/X-SQL on Windows
