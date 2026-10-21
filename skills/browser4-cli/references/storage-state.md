---
title: "Storage Management"
description: "Reference for cookie, localStorage, sessionStorage, and storage state commands. Save and restore browser storage state (cookies and localStorage; sessionStorage is not persisted)."
tier: catalog
---

# Storage Management

## Overview

Manage cookies, localStorage, sessionStorage, and browser storage state.

## Quick Index

| Topic | Section | Description |
|-------|---------|-------------|
| Save / restore full state | [Storage State](#storage-state) | `state-save` / `state-load` (cookies + localStorage) |
| Cookies | [Cookies](#cookies) | `cookie-list`, `cookie-get`, `cookie-set`, `cookie-delete`, `cookie-clear` |
| Local storage | [Local Storage](#local-storage) | list / get / set / delete / clear localStorage commands |
| Session storage | [Session Storage](#session-storage) | list / get / set / delete / clear sessionStorage commands |
| Common patterns | [Common Patterns](#common-patterns) | auth state reuse, save/restore roundtrip |

## Storage State

Save and restore browser storage state (cookies and localStorage). Note: sessionStorage is not persisted — it is intentionally scoped to the browsing session.

### Save Storage State

```bash
# Save to auto-generated filename in the CLI snapshot directory
# (.browser4-cli/snapshot/storage-state-{timestamp}.json) — same output
# location as extract/screenshot/snapshot captures.
browser4-cli state-save

# Save to a specific file path (relative paths resolve from the working dir)
browser4-cli state-save my-auth-state.json
```

### Restore Storage State

```bash
# Load storage state from file
browser4-cli state-load my-auth-state.json

# Cookies are restored to the browser's cookie jar immediately (visible via
# cookie-list). Reload/open the page only if you need them sent with the next
# HTTP request.
browser4-cli open https://example.com
```

### Storage State File Format

The saved file contains:

```json
{
  "cookies": [
    {
      "name": "session_id",
      "value": "abc123",
      "domain": "example.com",
      "path": "/",
      "expires": 1735689600,
      "httpOnly": true,
      "secure": true,
      "sameSite": "Lax"
    }
  ],
  "origins": [
    {
      "origin": "https://example.com",
      "localStorage": [
        { "name": "theme", "value": "dark" },
        { "name": "user_id", "value": "12345" }
      ]
    }
  ]
}
```

The cookie objects are a pass-through of the backend's storage state with two normalizations:
`expires` becomes an integer, and the attribute key set is **fixed**:

- `name`, `value`, `domain`, `path`, `expires`, `httpOnly`, `secure`, `sameSite` are always
  present;
- an attribute the browser did not report is `null` — a session cookie has `"expires": null`,
  and a cookie without an explicit SameSite policy has `"sameSite": null`;
- attributes the browser *does* report that are not in that list (e.g. `partitionKey`) are
  passed through untouched.

`cookie-list` prints the same cookie objects, so the two JSON surfaces agree.

## Cookies

### List All Cookies

```bash
browser4-cli cookie-list
```

Output is always JSON — an array of cookie objects, sorted by domain, then path, then name,
with `expires` printed as an integer Unix timestamp and attributes the browser did not report
printed as `null` (see [Storage State File Format](#storage-state-file-format)).

### Filter Cookies by Domain

```bash
browser4-cli cookie-list --domain example.com
```

### Filter Cookies by Path

```bash
browser4-cli cookie-list --path /api
```

### Get Specific Cookie

```bash
browser4-cli cookie-get session_id
```

### Set a Cookie

When `--domain` is omitted, the cookie is scoped to the current page domain.

```bash
# Basic cookie (scoped to current page domain)
browser4-cli cookie-set session abc123

# Cookie with options
browser4-cli cookie-set session abc123 --domain example.com --path / --httpOnly --secure --sameSite Lax

# Cookie with expiration (Unix timestamp)
browser4-cli cookie-set remember_me token123 --expires 1735689600
```

> **Git Bash note:** `--path` defaults to `/`, so from Git Bash simply **omit** the flag —
> `./b4w.ps1 cookie-set session_id abc123 --domain localhost --httpOnly --secure` needs no
> workaround. When you do need a `/`-leading value, a bare `--path /` (like any `/`-leading
> argument) is rewritten by Git Bash's MSYS path conversion before the command starts and
> arrives as `C:/Program Files/Git/`; the equals spelling `--path=/` is rewritten the same
> way. Both spellings are caught by the `b4w.ps1` guard, which fails fast and prints the
> de-converted command to re-run. Two working invocations for a leading-slash value:
>
> ```bash
> ./b4w.sh cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
> MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 cookie-set session_id abc123 --path / --httpOnly --secure
> ```
>
> The same rewrite applies to `cookie-list --path /` — omit the flag to list every path.
>
> Note: an earlier guard let `--path=/` slip through, and the command then failed later with
> `option '--path' was rejected: 'C' is not a valid cookie path` — a value the user never
> typed. The current guard also matches the part after `=`, the split `--path=C` + `/…` form
> MSYS produces, and a `--name=<drive>:/…` signature, so both spellings are rejected up front
> with the same actionable message.

### Delete a Cookie

```bash
browser4-cli cookie-delete session_id
```

### Clear All Cookies

```bash
browser4-cli cookie-clear
```

## Local Storage

### List All localStorage Items

```bash
browser4-cli localstorage-list
```

### Get Single Value

```bash
browser4-cli localstorage-get token
```

### Set Value

```bash
browser4-cli localstorage-set theme dark
```

### Set JSON Value

```bash
browser4-cli localstorage-set user_settings '{"theme":"dark","language":"en"}'
```

### Delete Single Item

```bash
browser4-cli localstorage-delete token
```

### Clear All localStorage

```bash
browser4-cli localstorage-clear
```

## Session Storage

### List All sessionStorage Items

```bash
browser4-cli sessionstorage-list
```

### Get Single Value

```bash
browser4-cli sessionstorage-get form_data
```

### Set Value

```bash
browser4-cli sessionstorage-set step 3
```

### Delete Single Item

```bash
browser4-cli sessionstorage-delete step
```

### Clear sessionStorage

```bash
browser4-cli sessionstorage-clear
```

## Common Patterns

### Authentication State Reuse

```bash
# Step 1: Login and save state
browser4-cli open https://app.example.com/login
browser4-cli snapshot
browser4-cli fill e1 "user@example.com"
browser4-cli fill e2 "password123"
browser4-cli click e3

# Save the authenticated state
browser4-cli state-save auth.json

# Step 2: Later, restore state and skip login
browser4-cli state-load auth.json
browser4-cli open https://app.example.com/dashboard
# Already logged in!
```

### Save and Restore Roundtrip

```bash
# Set up authentication state
browser4-cli open https://example.com
browser4-cli eval "() => { document.cookie = 'session=abc123'; localStorage.setItem('user', 'john'); }"

# Save state to file
browser4-cli state-save my-session.json

# ... later, in a new session ...

# Restore state
browser4-cli state-load my-session.json
browser4-cli open https://example.com
# Cookies and localStorage are restored!
```

## Security Notes

- Never commit storage state files containing auth tokens
- Add `*.auth-state.json` to `.gitignore`
- Delete state files after automation completes
- Use environment variables for sensitive data
