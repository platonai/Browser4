# browser4-profile-import

Import browser personal data from your system **Chrome / Edge / Safari** into
Browser4-managed profiles — bookmarks, history, passwords, cookies and
extensions — via the `profile_import` tool domain.

## What it does

| Source | Bookmarks | History | Passwords | Cookies | Extensions |
|---|---|---|---|---|---|
| Chrome | ✅ (whole profile) | ✅ | 🟡 opt-in copy | ✅ | ✅ |
| Edge | ✅ (whole profile) | ✅ | 🟡 opt-in copy | ✅ | ✅ |
| Safari | ✅ (plist → Chrome JSON) | ❌ | ❌ (Keychain) | ✅ (binarycookies → JSON) | ❌ (signed apps) |

- **Chrome / Edge**: copies the whole profile directory (same approach as
  agent-browser's `--profile <name>`): `Bookmarks`, `History`, `Login Data`,
  `Network/Cookies`, `Extensions/`, IndexedDB — excluding caches and lock
  files. The source browser must be closed first.
- **Safari (macOS)**: converts `Bookmarks.plist` into a Chrome `Bookmarks`
  JSON file and `Cookies.binarycookies` (unencrypted binary format) into a
  cookies JSON array.

## Tools

- `profile_import.list_sources` — discover installed browsers and profiles
- `profile_import.import(source, profile?, data?, into?)` — import into a
  snapshot directory under `~/.browser4/imports/`

`data` is a comma-separated subset of
`bookmarks,history,passwords,cookies,extensions` (default: all).

### CLI surface

The tools declare named CLI commands via `ToolSpec.cliName` — the CLI
discovers them from `GET /mcp/tools/specs` with no CLI code change:

```bash
browser4-cli profile sources                 # = profile_import.list_sources
browser4-cli profile import --source chrome --data bookmarks,cookies
browser4-cli profile import --source safari --into prototype
```

(An equivalent built-in `profile-import` command also exists for the same
tools.)

## Example

```
# MCP tool call
profile_import.import({ "source": "chrome", "profile": "Work", "data": "bookmarks,cookies" })

# Result
{
  "importDir": "~/.browser4/imports/chrome-Profile 1-20260825-103000",
  "profileDir": "~/.browser4/imports/chrome-Profile 1-20260825-103000/profile/Profile 1",
  "browser": "chrome",
  "sourceProfile": "chrome:Profile 1",
  "filesCopied": 214,
  "data": ["bookmarks", "cookies"],
  "warnings": ["Passwords were not imported. ..."],
  "nextStep": "browser4-cli open --profile <profileDir>"
}
```

Then mount the snapshot with `browser4-cli open --profile <profileDir>`
(backend support fixed in 4.14: the `profilePath` capability now reaches the
browser launch).

## Security

- Passwords are **not** imported by default. Login Data is encrypted with
  OS-bound keys (DPAPI / Keychain / app-bound); only copy it with
  `profileimport.allow.passwords=true` (same machine + same user only).
- Cookies are never decrypted off-line: Chromium cookies travel with the
  whole profile and are decrypted by Chrome itself at launch; Safari cookies
  are unencrypted by design.
- Snapshots contain sensitive data — clean them up after mounting.

## Configuration

| Property | Default | Description |
|---|---|---|
| `profileimport.enabled` | `true` | Enable/disable the plugin |
| `profileimport.import.dir` | `~/.browser4/imports` | Snapshot root directory |
| `profileimport.allow.passwords` | `false` | Copy `Login Data` as-is |

## Build

```bash
mvn package -pl browser4-plugins/browser4-profile-import -am -DskipTests
# deploy
cp browser4-plugins/browser4-profile-import/target/browser4-profile-import-*.jar <browser4>/plugins/
```

See also:

- `docs-dev/copilot/browser-data-import-eval.md` — feasibility evaluation
- `docs-dev/copilot/profile-import-design.md` — design & milestones
