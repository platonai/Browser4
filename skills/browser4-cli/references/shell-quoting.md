---
title: "Shell Quoting on Windows — Workaround Guide"
description: "How to avoid shell-quoting breakage when passing complex JS or X-SQL to browser4-cli on Windows / Git Bash. Read when inline --sql or eval expressions fail or get mangled."
tier: procedure
---

# Shell Quoting on Windows — Workaround Guide

## Quick Start

Avoid the quoting trap entirely — never inline complex expressions on Windows:

```bash
browser4-cli htmlsnapshot query --sql @query.sql    # SQL from file (recommended)
browser4-cli htmlsnapshot query --sql-stdin         # SQL from stdin
browser4-cli eval --file script.js                  # JS from file
browser4-cli eval --base64 "…"                      # JS as base64
```

[When to Use](#when-to-use) below explains when you are at risk; [Patterns](#patterns) has the full workaround recipes.

When running `browser4-cli` under Git Bash (or any POSIX shell on Windows), inline expressions pass through **four layers of quote interpretation**: Bash → `cargo run` → CLI argument parser → browser's JS engine. Each layer strips or reinterprets quotes, making correct escaping nearly impossible for complex JavaScript or X-SQL.

> **This is the trap warned about in [SKILL.md §5 Critical Warnings](../SKILL.md).** That section states the problem in one line; this file is the detailed workaround workflow. The warning text is not repeated here.

## When to Use

You hit this whenever you need to pass **complex JS or X-SQL** to `eval` / `htmlsnapshot query` / `htmlsnapshot inspect` on Windows — anything with nested quotes, backticks, or `$` signs. Simple single-argument commands are unaffected.

## How It Works

File-based and stream-based inputs bypass the shell entirely: the bytes are read directly by the CLI, so no layer of quote interpretation touches them. Base64 is shell-safe because its alphabet (`A–Z a–z 0–9 + / =`) contains no characters any shell treats specially.

## Patterns

### Problem: inline `eval` with nested quotes is mangled

```
# This FAILS on Windows because double quotes inside double quotes get mangled:
eval "JSON.stringify({searchVal: document.querySelector('input[placeholder*=\"Search\"]')?.value})"
```

Bash sees `\"` and strips one layer of escaping; the remaining quotes then interact with `cargo run` argument parsing. Single quotes, double quotes, backticks, and `$` signs all interact unpredictably.

**Solution:** read the script from a file or stream instead of the command line.

```bash
# 1. Write your JavaScript to a file (any text editor)
echo "document.querySelector('input.search-filter-input').value" > get-value.js

# 2. Evaluate it via --file — no quoting issues
browser4-cli eval --file get-value.js

# Or use heredoc for inline scripts (Bash only):
browser4-cli eval --stdin << 'EOF'
document.querySelector('input[placeholder*="Search"]')?.value
EOF

# Or pipe from echo:
echo 'JSON.stringify(document.querySelectorAll("a.job-link"))' | browser4-cli eval --stdin --json
```

### Problem: inline `--sql` with double-quoted CSS selectors breaks

**Solution:** use `@file`, `--sql-stdin`, or `--sql-base64`.

```bash
cat > query.sql << 'SQLEOF'
SELECT DOM_FIRST_TEXT(DOM, '.title') AS title
FROM DOM_LOAD_AND_SELECT(@url, '.product-card', 1, 48)
SQLEOF

browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```

### Problem: `htmlsnapshot inspect --selector "..."` with special chars

**Solution:** use `@file`, `--stdin`, or `--selector-base64`.

```bash
echo 'a[href]' | browser4-cli htmlsnapshot inspect --stdin
```

## Flags / Options Cheat Sheet

| Instead of | Use | Example |
|---|---|---|
| `eval "complex JS"` | `eval --file` | `eval --file script.js` |
| | `eval --stdin` | `echo 'document.title' \| browser4-cli eval --stdin` |
| | `eval --base64` | `eval --base64 ZG9jdW1lbnQudGl0bGU=` |
| `htmlsnapshot query --sql "..."` | `--sql @file` | `--sql @query.sql` |
| | `--sql-stdin` | `--sql-stdin < query.sql` |
| | `--sql-base64` | `--sql-base64 <base64>` |
| `htmlsnapshot inspect --selector "..."` | `@file` | `htmlsnapshot inspect @selectors.txt` |
| | `--stdin` | `echo 'a[href]' \| htmlsnapshot inspect --stdin` |
| | `--selector-base64` | `--selector-base64 <base64>` |

## Agent `b4.run`: one PowerShell layer, no stdin

`b4.run("…")` inside an agent task is not a shell you can pipe into. On Windows the
command text is handed to PowerShell as an **encoded** command (`pwsh -EncodedCommand`),
so the quoting you write survives into the CLI — `b4.run("eval --json \"1+1\"")` reaches
`eval` with one quoted argument instead of a `ParserError` (fixed 2026-10-29; before that
Java's argv quoting was undone by PowerShell's own `-Command` parser).

Two consequences:

- The text is still **PowerShell** syntax: quote `@file` references (`--sql "@query.sql"`)
  and do not use `cmd.exe` builtins (`dir /b`, `%VAR%`).
- There is **no stdin**: a command that reads it sees EOF at once. `eval --js` is a
  *bool* shorthand for `eval --stdin`, so `b4.run("eval --js \"…\"")` fails immediately
  with `Error: Stdin was empty …` (exit 2) instead of hanging for the whole timeout. Pass
  the JavaScript with `eval --file <path>` or as a quoted positional expression.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `--sql "…"` output is mangled or empty | Shell reinterprets nested quotes (Windows/Git Bash) | Use `--sql @file.sql` or `--sql-stdin` |
| `eval "…"` returns `null` or syntax errors | Quotes stripped across the 4 shell layers | Use `--file` / `--stdin` / `--base64` |
| Agent `b4.run("eval --js \"…\"")` exits 2 with `Stdin was empty` | `--js` is a bool shorthand for `--stdin`, and `b4.run` has no stdin | Use `eval --file <path>` or a quoted positional (`eval "1+1"`) |
| `htmlsnapshot inspect --selector "…"` breaks on special chars | `$`, backticks, or quotes in the selector | Use `@file`, `--stdin`, or `--selector-base64` |
| A `/`-leading value is mangled | MSYS path conversion | Omit `--path` if `/` is the default, or use `./b4w.sh` / `MSYS2_ARG_CONV_EXCL='*'` |

## PowerShell-Specific: `@` Splatting

In **PowerShell**, `@` is the [splatting operator](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_splatting). When an unquoted argument starts with `@`, PowerShell tries to interpret it as a splat variable:

```powershell
# This FAILS — PowerShell sees @file as a splat variable:
browser4-cli crawl --sql @.test-sessions/extract.sql

# This WORKS — quoting prevents splat interpretation:
browser4-cli crawl --sql "@.test-sessions/extract.sql"
```

**Always quote `@file` paths in PowerShell.** The quotes are stripped before the argument reaches the CLI, so the `@` prefix is still recognized.

## PowerShell-Specific: CSS Attribute Selectors

CSS attribute selectors use double quotes (`[class*="product-title"]`), which collide with PowerShell's double-quote string syntax. A `\"` inside a double-quoted PowerShell string is a literal backslash + quote (PowerShell escapes with the backtick `` ` ``, not backslash), so the selector reaches the CLI truncated (`[class*="`).

Wrap the selector in **single quotes** — PowerShell passes single-quoted strings verbatim:

```powershell
# This FAILS — \" is a literal backslash+quote, selector is truncated:
browser4-cli htmlsnapshot get all text "[class*=\"product-title\"]"

# This WORKS — single quotes pass the selector verbatim:
browser4-cli htmlsnapshot get all text '[class*="product-title"]'
```

**In PowerShell, use single quotes around CSS selectors that contain double quotes.**

## Git Bash / MSYS2: leading-slash values become Windows paths

MSYS2 (Git Bash) rewrites arguments that begin with `/` into Windows paths when it spawns a native executable (`pwsh`, `browser4-cli.exe`). A pattern value like `/product/` silently arrives as `C:/Program Files/Git/product/`:

```bash
# Git Bash → the value below is converted before it reaches the CLI:
# the pattern matches nothing → the crawl filters out every link.
browser4-cli crawl "https://example.com/index.html" -d 2 -ol "a.product" -olp "/product/"
```

The result is a silently wrong crawl: only the seed page is reported (`Crawl completed. 1 pages found.`), exit code 0, no hint of the mangled pattern.

**`./b4w.sh` neutralizes this** — it exports `MSYS2_ARG_CONV_EXCL='*'` around its `pwsh` invocation, so all arguments pass through unconverted. Use `./b4w.sh` from Git Bash whenever a command needs a `/`-leading value.

**`./b4w.ps1` called from Git Bash is affected too** — Git Bash runs the script by spawning the native `pwsh.exe`, and conversion applies to every argument of that spawn, regardless of quoting. Only a real PowerShell console (or cmd) is conversion-free.

`b4w.ps1` detects the rewritten value and fails fast instead of forwarding it, printing the
de-converted command to re-run. Its check covers the bare form, the part after `=`, the split
`--path=C` + `/…` form that MSYS can produce, and a `--name=<drive>:/…` signature — so
`--path=/` is caught as well.

**Workarounds:**
- Prefer `./b4w.sh` (conversion disabled for all arguments).
- Omit the flag when its default is already a `/` value — `cookie-set --path` defaults to `/`:
  `./b4w.ps1 cookie-set session_id abc123 --domain localhost --httpOnly --secure`.
- Use a value that does not start with `/`: `-olp "product/"` (instead of `-olp "/product/"`),
  `-olp "ec/dp"`.
- Or export the exclusion yourself before invoking anything:
  `export MSYS2_ARG_CONV_EXCL='*'` (older equivalent: `MSYS_NO_PATHCONV=1`).

Applies to any value that legitimately starts with `/` and is not an existing file path — `-olp`/`--out-link-pattern` (e.g. `/product/`), `snapshot grep` patterns like `/ec/dp/`, and similar. The CLI prints a warning when a pattern value looks like a mangled Windows path (contains `:/`).

`cookie-set --path /` is the same case, and its `--path=/` spelling is rewritten identically —
both need the same workaround (or just omit the flag: `cookie-set --path` defaults to `/`).

## Inside an agent run: who parses the line?

Two different shells sit under the agent's tools, and syntax that works in one
fails in the other:

| Tool | Executed as | Rules |
|---|---|---|
| `b4.run(args=…)` | `pwsh -Command "& browser4-cli …"` | PowerShell parses `args`: quote `@file` (`'--sql @q.sql'`), `&&` works, `dir /b` does not |
| `coding.shell` | `cmd.exe` | POSIX tools (`head`, `sed`) and `$()` fail; pipes/redirection are restricted |
| `coding.shell` inline scripts | refused — `PERMISSION_DENIED \| code=COMMAND_NOT_ALLOWED` | `powershell -Command …` / `bash -c …` are blocked by the command guard |

Observed 2026-10-28 (20-product supervision run): an unquoted `@.b4scratch/x-asin.sql`
inside `b4.run` produced 18 `ParserError`s — and the Chinese parse error came back as
mojibake, so the model could not read it. The same command with the `@file` quoted
worked on the first retry. Write `@file` references quoted, and keep each shell's
syntax inside its own tool.

## Why This Works

- **`--file`** — File content is read directly; the shell never interprets it.
- **`--stdin`** — Stdin content is passed as raw bytes; no shell interpolation.
- **`--base64`** — Base64 strings contain only alphanumeric characters and `+/=` — safe in any shell.
- **`@file`** — The `@` prefix tells the CLI to read from a file instead of interpreting the argument as a CSS selector / SQL string. **In PowerShell, always quote `@file` paths** (`"@path/to/file"`) to prevent `@` from being interpreted as the splatting operator.
