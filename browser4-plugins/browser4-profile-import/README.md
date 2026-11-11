# browser4-profile-import

Import browser personal data from your system **Chrome / Edge / Safari** into
Browser4-managed profiles — bookmarks, history, passwords, cookies and
extensions — via the `profile_import` tool domain.

Maven coordinates: `ai.platon.pulsar:browser4-profile-import`.

**Build line:** `4.16.1-SNAPSHOT` (parent `browser4-pdk`). The plugin manifest
in `src/main/resources/META-INF/browser4-plugin.json` owns a separate plugin
version; Maven filters the manifest's `sdkVersion` from the project version at
build time.

## What it does

| Source | Bookmarks | History | Passwords | Cookies | Extensions |
|---|---|---|---|---|---|
| Chrome | ✅ (whole profile) | ✅ | 🟡 opt-in copy | ✅ | ✅ |
| Edge | ✅ (whole profile) | ✅ | 🟡 opt-in copy | ✅ | ✅ |
| Safari | ✅ (plist → Chrome JSON) | ❌ | ❌ (Keychain) | ✅ (binarycookies → JSON) | ❌ (signed apps) |

- **Chrome / Edge**: copies the whole profile directory (same approach as
  agent-browser's `--profile <name>`): `Bookmarks`, `History`, `Login Data`,
  `Network/Cookies`, `Extensions/`, IndexedDB — excluding caches and lock
  files. The user-data-level `Local State` is copied alongside the profile so
  the OS-bound encryption key references stay valid. The source browser must
  be closed first (`SingletonLock` is checked).
- **Safari (macOS)**: converts `Bookmarks.plist` into a Chrome `Bookmarks`
  JSON file and `Cookies.binarycookies` (unencrypted binary format) into a
  cookies JSON array. History, passwords and extensions are not converted.
- **Chrome detection** also recognises Chromium and Brave installs (reported
  under the `chrome` source); Edge is detected separately.

🟡 A default/full Chromium import drops passwords with a warning. Naming
`passwords` in an explicit subset (`--data passwords`) is an error unless
`profileimport.allow.passwords=true`.

## Tools

- `profile_import.list_sources()` — discover installed browsers and profiles
- `profile_import.import(source, profile?, data?, into?)` — import into a
  snapshot directory under `~/.browser4/imports/`, optionally landing the
  result in a Browser4-managed profile (`into`)

`source` is `chrome`, `edge` or `safari`. `profile` is a Chrome/Edge profile
directory or display name (resolved via exact directory, case-insensitive
display name, then case-insensitive directory; ambiguous names are an error);
it defaults to the first profile and is ignored for Safari.

`data` is a comma-separated subset of
`bookmarks,history,passwords,cookies,extensions` (default: all). Chromium data
is pruned after the copy; Safari reports unsupported types in `warnings`
instead of failing.

`into` controls where the import lands:

| Value | Landing directory | Notes |
|---|---|---|
| `temp` (default) | `<browser4.data.dir>/.browser4/imports/<browser>-<profile>-<timestamp>/` | Snapshot only; mount it with `open --profile`. |
| `prototype` | `<browser4.data.dir>/.browser4/browser/chrome/prototype/google-chrome/` | Populates the prototype profile used by `SEQUENTIAL` / `TEMPORARY` modes. |
| `default` | `<browser4.data.dir>/.browser4/browser/chrome/default/PULSAR_CHROME/` | Populates the default context profile directory. |

`<browser4.data.dir>` falls back to the user home when the property is not set,
so the defaults are `~/.browser4/imports/`,
`~/.browser4/browser/chrome/prototype/google-chrome/` and
`~/.browser4/browser/chrome/default/PULSAR_CHROME/`.

`prototype` and `default` refuse a non-empty target (delete it first, or use
`--into temp`) and add `landedAt` to the result. For Chromium imports they also
copy `Local State` next to the profile. For Safari imports only the converted
`Bookmarks` file is copied to the landing directory; `cookies.json` stays in
the snapshot, and a landing requested with only cookies can therefore be empty.

The plugin also implements `ToolMount`: at `ApplicationReadyEvent` it registers
the import service as the `profile_import` custom target when an
`AgentToolManager` bean is present, so in-session LLM agents (for example
`RobustBrowserAgent`) can call the same tools. MCP/CLI calls pass a neutral
receiver and do not need that registration.

### Result shapes

Chromium imports return `importDir`, `profileDir`, `browser`,
`sourceProfile`, `filesCopied`, `data`, `warnings` and `nextStep`. Safari
imports return `importDir`, `bookmarksImported`, `cookiesImported`, `data`,
`warnings` and `nextStep`; they have no `profileDir` or `filesCopied` because
the output is a set of converted files rather than a profile copy.

`meta.json` records `createdAt`, `browser`, `sourceProfile`, `data` and
`filesCopied` (for Safari, the number of converted bookmarks plus cookies). It
does not contain credentials.

`list_sources` returns Chrome and Edge profile lists (each entry has
`directory`, `name`, `userDataDir` and `profileDir`) and a Safari map with
`bookmarks`, `history`, `cookies` and `keychain` paths (or `null` when absent).
The Safari map is empty on non-macOS hosts.

### CLI surface

The tools declare named CLI commands via `ToolSpec.cliName` — the CLI
discovers them from `GET /mcp/tools/specs` with no CLI code change:

```bash
browser4-cli profile sources                 # = profile_import.list_sources
browser4-cli profile import --source chrome --data bookmarks,cookies
browser4-cli profile import --source chrome --profile "Work" --into prototype
browser4-cli profile import --source safari --into prototype
```

An equivalent first-class built-in `profile-import` command also exists for
the same tools, with `--list-sources`, `--source`, `--profile`, `--data`,
`--into` and `--json`:

```bash
browser4-cli profile-import --list-sources
browser4-cli profile-import --source chrome --data bookmarks,cookies --json
```

The generic plugin form works too: `browser4-cli plugin profile_import import
--source chrome`.

## Snapshot layout

```text
~/.browser4/imports/
  chrome-Default-20260825-103000/     # Chromium snapshot
    meta.json                         # browser, source profile, data set, counts
    profile/
      Local State                     # user-data-level encryption references
      Default/
        Bookmarks
        History
        Network/Cookies
        Extensions/
  safari-Default-20260825-103000/     # Safari snapshot
    meta.json
    profile/Bookmarks                 # converted Chrome JSON
    cookies.json                      # converted cookies array
```

The snapshot directory name sanitizes the profile directory
(`[^A-Za-z0-9._-]` becomes `_`, so `Profile 1` → `Profile_1`); the profile
directory inside `profile/` keeps its original name. `Local State` lives next
to the profile directory, matching a real Chrome user-data directory.

## Example

```
# MCP tool call
profile_import.import({ "source": "chrome", "profile": "Work" })

# Result
{
  "importDir": "~/.browser4/imports/chrome-Profile_1-20260825-103000",
  "profileDir": "~/.browser4/imports/chrome-Profile_1-20260825-103000/profile/Profile 1",
  "browser": "chrome",
  "sourceProfile": "chrome:Profile 1",
  "filesCopied": 214,
  "data": ["bookmarks", "cookies", "extensions", "history"],
  "warnings": ["Passwords were not imported. ..."],
  "nextStep": "browser4-cli open --profile <profileDir>"
}
```

Any import with `into` set to `prototype` or `default` also returns
`"landedAt": "<target dir>"`. Safari imports report converted-item counts
instead of a profile copy:

```json
{
  "importDir": "~/.browser4/imports/safari-Default-20260825-103000",
  "bookmarksImported": 42,
  "cookiesImported": 17,
  "data": ["bookmarks", "cookies"],
  "warnings": [],
  "nextStep": "Mount the copied Bookmarks file into a target profile while the browser is closed, or convert cookies.json for state-load / cookie-set."
}
```

Then mount the Chromium snapshot with
`browser4-cli open --profile <profileDir>`; the backend consumes the
`profilePath` capability.

Safari's `cookies.json` is a bare JSON array of cookie objects, not a
`state-load` storage-state file. To restore cookies, wrap it as
`{"cookies": [ ... ], "origins": []}` for `browser4-cli state-load`, converting
`expires` to a numeric epoch if cookie expiry matters (the loader ignores the
ISO `expires` string and `expirationDate` key), or set cookies individually
with `browser4-cli cookie-set`.

## Security

- Passwords are **not** imported by default. Login Data is encrypted with
  OS-bound keys (DPAPI / Keychain / app-bound); only copy it with
  `profileimport.allow.passwords=true` (same machine + same user only). The
  copy is byte-for-byte — the plugin never decrypts it.
- Cookies are never decrypted off-line: Chromium cookies travel with the
  whole profile and are decrypted by Chrome itself at launch; Safari cookies
  are unencrypted by design.
- Snapshot directories contain sensitive data — clean them up after mounting.
  `meta.json` records paths, the data set and counts only; no credentials are
  written there.
- Importing is read-only with respect to the source browser: the plugin
  copies files and never modifies or deletes the source profile.

## Configuration

`profileimport.enabled` is a normal Spring property; the import root and
password opt-in are read from JVM system properties (`-D...`).

| Property | Default | Description |
|---|---|---|
| `profileimport.enabled` | `true` | Enable/disable the plugin |
| `profileimport.import.dir` | `<browser4.data.dir>/.browser4/imports` | Snapshot root directory (system property) |
| `profileimport.allow.passwords` | `false` | Copy `Login Data` as-is (system property) |

`browser4.data.dir` follows the same precedence: it is read as a JVM system
property and falls back to the user home. For example:

```bash
java -Dprofileimport.import.dir=/data/b4-imports \
     -Dprofileimport.allow.passwords=true ...
```

## Installation

The runtime bundle deliberately does **not** ship this plugin because it needs
`dd-plist`, which the bundle's `lib/` does not carry. Install it on demand:

```bash
# build/install the plugin into the local Maven repository
mvn install -pl browser4-plugins/browser4-profile-import -DskipTests

# install the thin JAR into the running backend, then restart it
browser4-cli plugin install \
  "browser4-plugins/browser4-profile-import/target/browser4-profile-import-<version>.jar"
browser4-cli stop
```

`dd-plist` is a compile-scope dependency declared in this module's `pom.xml`;
the plugin is a thin JAR and does not shade it. Put the matching `dd-plist`
JAR on the backend classpath (for example in the bundle's `lib/` directory)
when Safari imports are needed, or shade it into a custom build.

## Project layout

```text
browser4-plugins/browser4-profile-import/
  pom.xml
  README.md
  src/main/kotlin/ai/platon/pulsar/profileimport/
    config/ProfileImportAutoConfiguration.kt   # beans + ToolMount registration
    service/
      SourceBrowserDetector.kt                 # Chrome/Edge/Safari discovery
      ChromeProfileReader.kt                   # Local State / profile resolution
      ProfileCopier.kt                         # whole-profile copy + exclusions
      SafariDataReader.kt                      # plist / binarycookies conversion
      ProfileImportService.kt                  # import orchestration + landing
    tools/ProfileImportToolExecutor.kt         # profile_import MCP tools
  src/main/resources/META-INF/
    browser4-plugin.json                       # plugin descriptor
    spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/kotlin/ai/platon/pulsar/profileimport/service/
    ChromeProfileReaderTest.kt
    ProfileCopierTest.kt
    ProfileImportServiceTest.kt
    SafariDataReaderTest.kt
```

## Build & test

```bash
# unit tests (offline; use temporary directories)
mvn -pl browser4-plugins/browser4-profile-import test

# package and deploy
mvn package -pl browser4-plugins/browser4-profile-import -am -DskipTests
cp browser4-plugins/browser4-profile-import/target/browser4-profile-import-*.jar <browser4>/plugins/
```

On Windows, use `Copy-Item` instead of `cp`:

```powershell
Copy-Item browser4-plugins/browser4-profile-import/target/browser4-profile-import-*.jar <browser4>/plugins/
```

The plugin is discovered from `META-INF/browser4-plugin.json` and its Spring
auto-configuration; adding or replacing the JAR requires a backend restart
(`browser4-cli stop`, then the next command restarts it).

## Limitations & troubleshooting

- **Source browser running**: the copy fails with a "close it completely"
  error when `SingletonLock` is found, and individual locked/unreadable files
  are logged and skipped. Close the source browser before importing.
- **No profile found**: the source browser is not installed under a known
  platform path, or its `Local State` file is missing/malformed. Run
  `profile sources` to see what was detected.
- **Ambiguous profile name**: two profiles share a display name; pass the
  profile directory (`Default`, `Profile 1`, …) instead.
- **Landing target not empty**: `prototype` and `default` refuse to overwrite.
  Delete the target first, or use `--into temp`.
- **Safari**: macOS only. History (`History.db`), passwords (Keychain) and
  extensions (signed apps) are not imported; the plugin emits a warning listing
  the unsupported types. Safari data paths vary by macOS release.
- **Passwords**: explicit Chromium imports fail unless
  `profileimport.allow.passwords=true`; even then they are only portable
  between sessions of the same user on the same machine, and may fail after a
  Chrome app-bound encryption upgrade.
- **Chromium version skew**: a profile copied from a much newer Chrome/Edge
  may be rejected or upgraded by the target browser; keep source and target
  versions reasonably close.
- **Cache/lock exclusions**: cache directories and lock files are skipped, so
  `filesCopied` is smaller than the raw directory size and the copy is a
  best-effort snapshot rather than a byte-for-byte clone.

See also:

- [`docs-dev/copilot/browser-data-import-eval.md`](../../docs-dev/copilot/browser-data-import-eval.md) — feasibility evaluation
- [`docs-dev/copilot/profile-import-design.md`](../../docs-dev/copilot/profile-import-design.md) — design & milestones
- [`skills/browser4-cli/references/browser-state-import.md`](../../skills/browser4-cli/references/browser-state-import.md) — manual state import procedure and the automated profile-import alternative
- [`skills/browser4-cli/references/plugins.md`](../../skills/browser4-cli/references/plugins.md) — plugin discovery & installation
