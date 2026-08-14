---
title: "Storage Management"
description: "Reference for cookie, localStorage, sessionStorage, and storage state commands. Save and restore browser storage state (cookies and localStorage; sessionStorage is not persisted)."
tier: catalog
---

# Storage Management

Manage cookies, localStorage, sessionStorage, and browser storage state.

## Storage State

Save and restore browser storage state (cookies and localStorage). Note: sessionStorage is not persisted — it is intentionally scoped to the browsing session.

### Save Storage State

```bash
# Save to auto-generated filename (storage-state-{timestamp}.json)
browser4-cli state-save

# Save to specific filename
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

## Cookies

### List All Cookies

```bash
browser4-cli cookie-list
```

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
