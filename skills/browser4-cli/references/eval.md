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

# ⚠ FIRST RULE: an async expression needs --await
browser4-cli eval --await "(async () => (await fetch('/api/x')).json())()"

# Machine-readable output (scalars become quoted strings)
browser4-cli eval --json "document.querySelectorAll('.product-card').length"

# Element-scoped read: the expression MUST be an arrow function
browser4-cli eval "element => element.textContent" --ref e5
```

- **`--await` is the first thing to check.** Without it an async expression returns an
  unresolved Promise; the backend reports that as a **failure** (`The expression returned
  an unresolved Promise… re-run with --await`) instead of pretending a value came back.
  A batch that returns `{}` is this case, not empty data.
- The expression runs against the live DOM; JS exceptions are surfaced as CLI errors.
- Only the **return value** is printed — `console.log` output is not part of eval's result
  (read it with `browser4-cli console`).
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

- **A Promise is not a value.** An expression that returns one without `--await` is
  reported as a failure with the fix named (`--await`); it is never silently rendered as
  `{}` or empty data.
- JS `undefined` prints a `null` line plus an explanation (`null  (undefined: …)`),
  because a bare `null` taught nothing about why there was no data. `--json` keeps the
  type-faithful `null` for it. Return a value (last expression or `return`) when the
  result matters.
- JS `null` prints as `null`. An empty string prints as `""`.
- Objects and arrays are serialized as valid JSON.
- `--json` wraps the result in the CLI's JSON envelope; scalar results become quoted strings, numbers/booleans/null pass through.
- Do not rely on telling `undefined`, `null` and `''` apart — have the expression return an
  explicit sentinel instead:
  `eval "typeof x === 'undefined' ? 'MISSING' : x"`.
- **`console.log()` output is not part of eval's own result** — only the expression's return
  value is printed. Read page logs with `browser4-cli console` (or `errors` for error-level
  entries only), and note that capture starts with the **first `console` read**: messages
  logged before it are not recorded, so run `browser4-cli console --clear` before the
  interaction (it also starts capture and empties the buffer), or read `console` early and
  again afterwards.
- Scripts written in the natural "compute and log" style therefore print only the value of the
  last statement (often `""`). Use `return` (or end with the value); the CLI prints a reminder
  when it detects `console.log` in the expression.

> Note: the CLI's `null` branch prints the literal text the backend sends. A main-world
> evaluation of `undefined` is carried as JSON `null`, so it prints `null`; check with
> `typeof` (or `--json`, where the value passes through as JSON null) when the difference
> matters.

### Execution World — Main World by Default, `--world isolated` for the Runtime

`eval` runs in the **page's own JavaScript world** by default. That is the world page scripts
run in, so anything the page defined is in scope:

```bash
browser4-cli eval "window.myApp.version"        # main world: resolves
browser4-cli eval "document.title"              # both worlds: same DOM
```

Use `--world isolated` to evaluate in the driver's own world instead — a separate context the
backend creates per main frame with `Page.createIsolatedWorld` (world name
`__browser4_runtime__`), where the injected runtime helpers live and where page scripts can
neither see nor modify what you write:

```bash
browser4-cli eval "typeof __browser4_runtime__" --world isolated   # "object"
```

The two worlds share the DOM but not JavaScript globals:

- A global written in the **main world** (`eval "window.__probe = 42"`) is visible to page
  scripts; the same write with `--world isolated` is not.
- A global written by the page (`window.myApp`) is **only** visible in the main world — reading
  it with `--world isolated` returns `null`, which is why the default changed: the same command
  used to read `null` for a value the page plainly had.
- `--world` accepts `main` (aliases: `page`, `default`) and `isolated` (alias: `runtime`). An
  unknown value is refused with the accepted names rather than silently picking one.

DOM reads such as `eval "document.querySelector(…)"` work in **both** worlds because they walk
the same DOM. Patching the DOM directly from `eval` (e.g. `el.textContent = "x"`) also lands in
that shared DOM, but it bypasses the page's own JS/framework state and event handlers, so the
page may not observe it as a real interaction — prefer `fill`/`click`/`type`.

When you need the page to own a value, do not write JS globals through `eval`:

```bash
# Let the page update its own state through its real inputs, then read the result back.
browser4-cli fill e3 "hello"                  # the page's own model/handlers run
browser4-cli eval "document.querySelector('#result').textContent"
```

`console.log` output produced by `eval` is captured by `browser4-cli console` in either world
(subject to the capture-start caveat above).

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

`console.log(...)` inside the file is not part of eval's result — only the returned object is
printed (the logged lines are readable with `browser4-cli console`).

## Flags

| Option | Description |
|--------|-------------|
| `eval "<expression>"` | Inline expression — best for simple reads |
| `--file <path>` | Read the expression from a file (also accepts `@<path>`) — best for multi-line scripts |
| `--stdin` / `--js` | Read the expression from stdin (`--js` is an alias) — best for heredocs and complex quoting |
| `--base64 <b64>` | Quoting-proof inline form (Windows: `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('expr'))`) |
| `--json` | Wrap the result in the CLI's JSON envelope |
| `--await` | Wait for the expression's Promise to resolve before returning the result — for `fetch`/async work (`eval --await "fetch('/api').then(r => r.json())"`) |
| `--wait-selector <css>` | Wait for a CSS selector to appear before evaluating — for SPA/React pages that render asynchronously |
| `--wait-timeout <ms>` | Max time to wait for `--wait-selector` (default: 30000) |
| `--ref <ref>` | Evaluate against one element; the expression receives it as its first argument and must be an arrow function |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Printed value is `null` or `""` although the script computes something | `console.log` output is not eval's result | End with the value or `return`; read logs with `console` |
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
