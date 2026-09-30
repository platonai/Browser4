---
title: "Import Browser State"
description: "Reuse logged-in state (cookies, localStorage) or an entire profile from your system Chrome/Edge inside Browser4-managed sessions. Covers attach + state-save/state-load, the deprecated SYSTEM_DEFAULT mode, and full-profile copy via PROTOTYPE or open --profile."
tier: procedure
---

# Import Browser State

## Quick Start

```bash
browser4-cli state-save my-state.json            # save the current session's web state (cookies + localStorage)
browser4-cli open --fresh https://example.com    # launch a Browser4-managed browser
browser4-cli state-load my-state.json            # restore cookies + localStorage into it
```

The recommended end-to-end path — attach to your running system browser, save its state, then load it into a managed browser — is step [1](#1-recommended-copy-web-state-cookies--localstorage) below.

Copy the state of your **system browser** (the Chrome/Edge you use daily) into a
**Browser4-managed browser** (a browser launched by `open`, using an isolated
`~/.browser4` profile directory).

Two levels of "state" exist:

| Level | What is copied | Supported today |
|---|---|---|
| **Web state** | Cookies + localStorage — enough to restore logins | ✅ Fully supported via `state-save` / `state-load` |
| **Full profile** | History, passwords, extensions, IndexedDB, cache, settings | ⚠️ Manual profile-directory copy only (with caveats) |

## When to Use

Use this when you need **logged-in state** (cookies + localStorage) from your system Chrome/Edge inside a Browser4-managed session — e.g. to access authenticated pages without logging in again. Prefer `state-save`/`state-load` web-state copies; full profile copies are manual, fragile, and only for special cases.

## How It Works

`state-save` serializes the session's cookies and localStorage into a JSON file; `state-load` writes them back into a browser session's cookie jar and storage, so logins survive without re-authentication. Full profile copies instead copy the entire system Chrome profile directory (history, extensions, passwords) — manual and fragile.

## Patterns

- [1. Web state copy (recommended)](#1-recommended-copy-web-state-cookies--localstorage) — attach → `state-save` → `open` → `state-load`
- [2. `SYSTEM_DEFAULT` profile mode](#2-system_default-profile-mode-deprecated) — deprecated; kept for legacy setups
- [3. Full profile copy](#3-full-profile-copy-prototype-mode--open---profile) — manual profile-directory copy for history/passwords/extensions

## Flags

The `state-save` / `state-load` / `attach` commands take positional arguments only; see [storage-state.md](storage-state.md) and [attach.md](attach.md) for their full command references.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Cookies restored but login still fails | Page not reloaded after `state-load` | `open` / reload the page so cookies are sent with the next request |
| `state-load` has no effect | State was saved from a different profile/origin | Save from the exact session you attached to; check the JSON's origins |
| Full profile copy broken | Chrome profile locked or version mismatch | Close the source browser first; copy only the needed subdirectories |

## 1. Recommended: copy web state (cookies + localStorage)

This is the officially supported path. Attach to your running system browser,
export its storage state to JSON, then import it into a Browser4-managed
session:

```powershell
# 1. Attach to the running system Chrome/Edge.
#    --extension is easiest: no debugging port needed, existing tabs stay intact.
browser4-cli attach --extension

# 2. Export cookies + current-origin localStorage from the system browser.
browser4-cli state-save system-auth.json

# 3. Disconnect (your system browser keeps running untouched).
browser4-cli close

# 4. Start a fresh Browser4-managed session.
browser4-cli open --fresh

# 5. Import the state. Cookies are restored to the jar immediately;
#    reload/open a page when you need them sent with the next request.
browser4-cli state-load system-auth.json
browser4-cli goto https://example.com/dashboard
```

If the system browser has remote debugging enabled, the same flow works with
`attach --cdp chrome` (channel name) or `attach --cdp http://localhost:9222`
(endpoint). See [attach.md](attach.md) for details.

### What `state-save` includes (and excludes)

Included:

- All cookies from the browser cookie jar (`Network.getAllCookies`).
- `localStorage` of the **active origin only** (the page currently open in the
  session when `state-save` runs).

Excluded (by design):

- `sessionStorage` — intentionally scoped to the browsing session.
- History, passwords, extensions, IndexedDB, cache, service workers, browser
  settings. Use a full profile copy (section 3) for those.

### Multiple-origin localStorage

`state-save` captures only the active origin's localStorage. To collect several
origins, navigate to each origin and save separately, then merge the JSON
`origins` arrays, or copy individual entries with `localstorage-get` /
`cookie-list`.

## 2. `SYSTEM_DEFAULT` profile mode (deprecated)

`browser.profile.mode=SYSTEM_DEFAULT` (or `--profile-mode SYSTEM_DEFAULT`)
launches Chrome pointing at the system's real default profile instead of a
copy.

**Do not rely on it for modern Chrome:** Chrome ≥ 143 refuses to debug the real
default profile, so this mode is effectively broken (see
https://github.com/platonai/Browser4/issues/162). The system browser also locks
its profile while running. Use section 1 for web state, or section 3 for a
full profile copy.

## 3. Full profile copy (PROTOTYPE mode / `open --profile`)

Browser4's `PROTOTYPE` profile mode maintains a managed prototype directory
(e.g. `~/.browser4/browser/chrome/prototype/google-chrome/`). All `SEQUENTIAL`
and `TEMPORARY` contexts inherit from the prototype. You can also point any
session at an arbitrary profile directory with `open --profile <path>`.

To make a full copy of your system profile:

1. **Fully close the system browser** (including background processes). The
   profile directory is locked while the browser runs.
2. Copy the profile directory, e.g.:

   ```powershell
   # Windows Chrome default profile
   Copy-Item -Recurse "$env:LOCALAPPDATA\Google\Chrome\User Data\Default" `
     "D:\profiles\chrome-system-copy" -Exclude "SingletonLock","SingletonSocket","SingletonCookie"
   ```

3. Either seed the prototype with it:

   ```bash
   # Copy into the Browser4 prototype context dir
   cp -r <copied-profile> ~/.browser4/browser/chrome/prototype/google-chrome/
   # Then run Browser4 with PROTOTYPE mode
   browser4-cli open --profile-mode prototype
   ```

   or mount it directly for one session:

   ```bash
   browser4-cli open --profile <copied-profile>
   ```

### Caveats for full profile copies

- **DPAPI encryption (Windows):** cookies and passwords are encrypted with the
  OS user's key. A copy works only on the same Windows user account; it is not
  portable across machines/users.
- **Chrome version mismatch:** profiles are forward-compatible within a
  reasonable window, but a profile written by a much newer Chrome can be
  rejected by an older Chromium. Keep source and target versions close.
- **Skip lock files:** never copy `SingletonLock` / `SingletonSocket` /
  `SingletonCookie`; they are per-process and cause startup failures.
- **Not an official one-command feature yet:** unlike section 1, there is no
  `profile-import` command — this is a manual procedure.

> **Automated alternative (4.14+):** the `browser4-profile-import` plugin
> exposes `profile_import.list_sources` and `profile_import.import` tools that
> discover Chrome/Edge/Safari profiles, copy the whole profile (excluding
> caches and lock files) into `~/.browser4/imports/<snapshot>/`, and convert
> Safari bookmarks/cookies into Browser4 formats. The copied profile mounts
> with `open --profile <dir>` (the `profilePath` capability now reaches the
> browser launch). Passwords are excluded by default for security.

## Comparison

| Path | State covered | Copied into managed browser | Status |
|---|---|---|---|
| attach → `state-save` → `state-load` | cookies + localStorage | ✅ Yes | ✅ Supported |
| `attach --extension` / `--cdp` (use directly) | everything live | ❌ No — drives the original browser | ✅ Supported |
| `SYSTEM_DEFAULT` mode | system default profile | ❌ No — shares it | ⚠️ Deprecated (Chrome ≥ 143) |
| Profile copy → PROTOTYPE / `open --profile` | nearly everything | ✅ Yes | ⚠️ Manual, with caveats |

## Security notes

- Storage-state JSON contains live auth tokens — never commit it, and delete it
  after use (`.gitignore` with `*.auth-state.json`).
- A full profile copy contains passwords and cookies: keep the copied directory
  protected and clean it up when done.
