---
title: "Shell Quoting on Windows — Workaround Guide"
description: "How to avoid shell-quoting breakage when passing complex JS or X-SQL to browser4-cli on Windows / Git Bash. Read when inline --sql or eval expressions fail or get mangled."
tier: procedure
---

# Shell Quoting on Windows — Workaround Guide

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

## Options Cheat Sheet

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

## Why This Works

- **`--file`** — File content is read directly; the shell never interprets it.
- **`--stdin`** — Stdin content is passed as raw bytes; no shell interpolation.
- **`--base64`** — Base64 strings contain only alphanumeric characters and `+/=` — safe in any shell.
- **`@file`** — The `@` prefix tells the CLI to read from a file instead of interpreting the argument as a CSS selector / SQL string. **In PowerShell, always quote `@file` paths** (`"@path/to/file"`) to prevent `@` from being interpreted as the splatting operator.
