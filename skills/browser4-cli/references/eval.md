---
title: "eval — Run JavaScript in the Page"
description: "Reference for the eval command: inline/--file/--stdin/--base64 evaluation, --json, element-scoped --ref evaluation, caveats (console.log, arrow functions)."
tier: procedure
---

# eval — Run JavaScript in the Page

Run a JavaScript expression against the **live DOM** of the current page and print its return value. `eval` sees login state, SPA updates and mutations made by earlier `fill`/`click`/`type` commands — it is the tool for live reads, complex transforms, and verification that needs real DOM state.

## Invocation forms

| Form | Command | Best for |
|---|---|---|
| Inline | `browser4-cli eval "document.title"` | Simple expressions |
| File | `browser4-cli eval --file script.js` | Multi-line scripts, no shell quoting |
| Stdin | `echo 'document.title' \| browser4-cli eval --stdin` | Heredocs / one-liners with complex quoting |
| Stdin shorthand | `browser4-cli eval --js` | `--js` is an alias for `--stdin` |
| Base64 | `browser4-cli eval --base64 <b64>` | Inline, quoting-proof (Windows PowerShell: `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('expr'))`) |
| JSON wrap | `browser4-cli eval --json "document.title"` | Machine-readable output (scalars get quoted) |
| Element-scoped | `browser4-cli eval "element => element.textContent" --ref e5` | Read a specific element's properties |

`--file` also accepts the `@`-prefix file convention used across the CLI: `eval --file "@script.js"` behaves like `--sql @query.sql`.

## Element-scoped evaluation (--ref / positional ref)

Pass an element ref (`e5`) positionally or via `--ref`, and the expression receives the element as its first argument. The expression **MUST be an arrow function**:

```bash
browser4-cli eval "element => element.textContent" e5       # text of e5
browser4-cli eval "element => element.getAttribute('href')" --ref e5
browser4-cli eval --file script.js e5                       # file content + positional ref
```

`element => element.property` works; bare `element.property` does **not** (the element is an argument, not a global).

## Return-value semantics

- Only the expression's **return value** is printed: `null` for JS null/undefined, `""` for an empty string, or the value itself otherwise. JS exceptions are surfaced as errors.
- **`console.log()` output is NOT captured.** Scripts written in the natural "compute and log" style print only the value of the last statement (often `null`). Use `return` (or end with the value) for anything you want to see — the CLI prints a reminder when it detects `console.log` in the expression.
- Objects and arrays are serialized as valid JSON.
- `--json` wraps the result in the CLI's JSON envelope; scalar results become quoted strings, numbers/booleans/null pass through.

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

## Windows / Git Bash quoting

- Prefer `--file`, `--stdin`, or `--base64` for anything beyond a trivial expression (see [shell-quoting.md](shell-quoting.md)).
- When invoking through `./b4w.ps1` from Git Bash, arguments are re-quoted by the bash→pwsh boundary; quote each argument individually (`./b4w.ps1 "eval" "--json" "document.title"`) or use `./b4w.sh`.

## See also

- [htmlsnapshot.md](htmlsnapshot.md) — capture-based extraction (stored/cached reads)
- [snapshot.md](snapshot.md) — accessibility tree with element refs for interaction
- [css-selector-bridge.md](css-selector-bridge.md) — bridging refs to CSS selectors
- [shell-quoting.md](shell-quoting.md) — quoting pitfalls for JS/X-SQL on Windows
