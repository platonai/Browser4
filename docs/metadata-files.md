# Metadata Files Reference

This document catalogues every persistent metadata file and directory marker used
by the `install`, `upgrade`, and `uninstall` commands — plus the standalone
installer scripts.  Each entry describes the file's schema, location, lifecycle,
and which command or script reads or writes it.

---

## Diagram

```
                               ┌─────────────────────────────────────────────┐
                               │          CLI binary install layer           │
                               │                                             │
      ┌──────────────────┐     │  cli/bin/.install-method   "npm"            │
      │ install-browser4- │     │  ~/.browser4/              cli-state.json  │
      │ cli.ps1 / .sh    │     │  ~/.browser4/sessions/     {name}.json     │
      └──────────────────┘     │                                             │
                               └─────────────────────────────────────────────┘
                               ┌─────────────────────────────────────────────┐
                               │          Runtime bundle layer               │
                               │                                             │
                               │  {data}/mirrors.json                        │
                               │  {cache}/mirror-preference.json             │
                               │                                             │
                               │  {data}/runtime/                            │
                               │  ├── .install.lock/                         │
                               │  │   └── pid                               │
                               │  ├── current.tag          "v4.11.0\n"       │
                               │  └── v4.11.0/                              │
                               │      ├── browser4-installation.json         │
                               │      ├── lib/              *.jar            │
                               │      ├── runtime/          JRE              │
                               │      └── bin/              launchers        │
                               │                                             │
                               │  {cache}/downloads/                         │
                               │  └── v4.11.0/                              │
                               │      ├── browser4-bundle-runtime-….tar.gz   │
                               │      └── browser4-bundle-runtime-….tar.gz   │
                               │          .sha256                            │
                               └─────────────────────────────────────────────┘
```

**Directory resolution** (from `cli/browser4-cli/src/state.rs`):

| Variable | Purpose |
|---|---|
| `{data}` | Runtime data — platform-conventional: `$XDG_DATA_HOME/browser4/`, `~/Library/Application Support/browser4/`, or `%APPDATA%/browser4/`. Override with `BROWSER4_RUNTIME_DIR`. |
| `{cache}` | Download cache — platform-conventional: `$XDG_CACHE_HOME/browser4/`, `~/Library/Caches/browser4/`, or `%LOCALAPPDATA%/browser4/`. When `BROWSER4_RUNTIME_DIR` is set, lives under `{BROWSER4_RUNTIME_DIR}/cache/`. |

---

## 1. `browser4-installation.json`

**Scope**: Per-version install record — one file per installed release tag.

| Property | Value |
|---|---|
| **Path** | `{data}/runtime/{tag}/browser4-installation.json` |
| **Format** | JSON |
| **Created by** | `browser4-cli install`, `browser4-cli upgrade` |
| **Read by** | `browser4-cli install` (already-installed check), `browser4-cli status`, legacy migration, auto-install on first use |
| **Deleted by** | `browser4-cli uninstall` (when the whole runtime dir is removed) |

### Schema

```json
{
  "tag": "v4.11.0",
  "asset_name": "browser4-bundle-runtime-linux-x64.tar.gz",
  "download_url": "https://github.com/platonai/Browser4/releases/download/v4.11.0/browser4-bundle-runtime-linux-x64.tar.gz",
  "installed_at": "2026-06-13T12:00:00+00:00"
}
```

| Field | Type | Description |
|---|---|---|
| `tag` | string | Normalised release tag (always has `v` prefix, e.g. `"v4.11.0"`). |
| `asset_name` | string | Platform-specific archive filename (e.g. `"browser4-bundle-runtime-linux-x64.tar.gz"`). |
| `download_url` | string | Full URL the archive was fetched from. |
| `installed_at` | string | ISO 8601 timestamp of when the metadata was written. |

### Lifecycle

1. **Write**: After the runtime bundle archive is extracted and the versioned
   directory is committed.  The file is written to the **staging** directory
   first, then atomically renamed into place alongside `lib/` and `runtime/`
   (`commit_installed_browser4_runtime` in `daemon.rs`).

2. **Read (current version)**: `read_installed_browser4_runtime_metadata()`
   resolves the active install via `current.tag`, then reads the metadata file
   inside that directory.

3. **Read (specific version)**: `read_installed_browser4_runtime_metadata_for()`
   reads metadata for a specific tag directory without consulting `current.tag`
   — used by the "already installed?" fast-path in `install_browser4_runtime()`.

4. **Gate**: `install_dir_contains_runtime()` treats the **existence** of this
   file as proof that the install committed successfully.  A directory missing
   this file is considered a partial/corrupt install and is re-downloaded.

### Defined in

`cli/browser4-cli/src/daemon.rs` — constant `BROWSER4_INSTALL_METADATA_FILE_NAME`,
struct `InstalledBrowser4RuntimeMetadata`.

---

## 2. `current.tag`

**Scope**: Singleton — one file that records which version is active.

| Property | Value |
|---|---|
| **Path** | `{data}/runtime/current.tag` |
| **Format** | Plain text (one line, trailing newline) |
| **Created by** | `browser4-cli install`, `browser4-cli upgrade` |
| **Read by** | `browser4-cli install`, `browser4-cli upgrade`, `browser4-cli status`, auto-install on first use, `browser4-cli stop`, `browser4-cli open` |
| **Deleted by** | `browser4-cli uninstall` (when the whole runtime dir is removed) |

### Content

```
v4.11.0
```

A single line containing the normalised release tag (always `v`-prefixed),
terminated by `\n`.

### Lifecycle

1. **Write**: `write_current_tag()` — called at the end of a successful install
   or upgrade (after the versioned directory is fully committed).  On Unix it
   also creates a best-effort `current` **symlink** pointing to the versioned
   directory (`{data}/runtime/current → v4.11.0/`), but the `current.tag` file
   is always the authoritative source.

2. **Read**: `read_current_tag()` — reads the file, trims whitespace, and
   returns the tag string.  Falls back to:
   - Legacy migration (`try_migrate_legacy_runtime()` — reads old
     `~/.browser4/lib/browser4-installation.json`, moves files, and writes
     `current.tag`).
   - Scanning for the newest versioned directory
     (`find_newest_versioned_install()`).

3. **Update**: On a successful install of a different version, the file is
   overwritten with the new tag.

### Defined in

`cli/browser4-cli/src/daemon.rs` — constant `CURRENT_TAG_FILE_NAME`, functions
`current_tag_file_path()`, `read_current_tag()`, `write_current_tag()`.

---

## 3. `mirrors.json`

**Scope**: Singleton — user-configurable download mirror list.

| Property | Value |
|---|---|
| **Path** | `{data}/mirrors.json` (overridable via `BROWSER4_MIRRORS_CONFIG` env) |
| **Format** | JSON |
| **Created by** | User (optional — built-in defaults are used when the file is absent) |
| **Read by** | `browser4-cli install`, `browser4-cli upgrade`, auto-install on first use |
| **Deleted by** | User |

### Schema

```json
{
  "mirrors": [
    {
      "name": "github",
      "base_url": "https://github.com/platonai/Browser4/releases",
      "supports_latest_resolution": true
    },
    {
      "name": "aliyun-oss",
      "base_url": "https://browser4.oss-cn-beijing.aliyuncs.com/releases",
      "supports_latest_resolution": true
    }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `mirrors` | array | Ordered list of download mirrors. The CLI tries them in array order and uses the first reachable one. |
| `mirrors[].name` | string | Human-readable label shown in log messages (e.g. `"github"`, `"corporate-cdn"`). |
| `mirrors[].base_url` | string | Base URL hosting release assets. Tagged downloads use `<base>/download/<tag>/<asset>`. Latest-download path is controlled by `latest_path`. Trailing slashes are normalised. |
| `mirrors[].supports_latest_resolution` | boolean | When `true`, the CLI can resolve the latest release without an explicit `--tag`. When `false`, speed tests are skipped for `latest` requests. Defaults to `false` when omitted. |
| `mirrors[].latest_path` | string | Path segment inserted between `base_url` and `asset_name` for latest downloads. Defaults to `latest/download` (GitHub Releases layout). Override to `download/latest` for mirrors like Aliyun OSS. Optional — omit for the default. |

### Lifecycle

1. **Load**: `load_mirrors()` — called at the start of every runtime bundle
   download.  Resolution order:
   - If `BROWSER4_RELEASES_BASE_URL` is set → single-mirror override (name:
     `"custom"`), skips the config file entirely.
   - If `BROWSER4_MIRRORS_CONFIG` is set to a path → reads that file.
   - Otherwise reads `{data}/mirrors.json`.
   - If the file is missing, empty, or unparseable → falls back to built-in
     defaults (GitHub → Aliyun OSS).

### Defined in

`cli/browser4-cli/src/daemon.rs` — constant `MIRRORS_CONFIG_FILE_NAME`, structs
`DownloadMirror`, `MirrorsConfig`, function `load_mirrors()`.

---

## 4. `mirror-preference.json`

**Scope**: Singleton — caches the fastest mirror from speed tests.

| Property | Value |
|---|---|
| **Path** | `{cache}/mirror-preference.json` |
| **Format** | JSON |
| **Created by** | `browser4-cli install`, `browser4-cli upgrade` (after concurrent speed tests complete) |
| **Read by** | `browser4-cli install`, `browser4-cli upgrade` (to skip re-testing a known-fast mirror) |
| **Deleted by** | `delete_mirror_preference_cache()` (called on download failure to force re-testing) |

### Schema

```json
{
  "selected_mirror": {
    "name": "github",
    "base_url": "https://github.com/platonai/Browser4/releases",
    "supports_latest_resolution": true
  },
  "tested_at": "2026-06-13T12:00:00+00:00",
  "download_speed_bps": 52428800
}
```

| Field | Type | Description |
|---|---|---|
| `selected_mirror` | object | Full `DownloadMirror` entry for the fastest mirror found. |
| `tested_at` | string | ISO 8601 / RFC 3339 timestamp of when the speed test ran. |
| `download_speed_bps` | integer | Measured throughput in bytes per second (higher is better). |

### Lifecycle

1. **Check**: `load_mirror_preference()` loads the file and validates:
   - The cached mirror is still in the current mirror list (checked by base URL,
     normalised for trailing slashes).
   - The preference has not expired (default TTL: **24 hours**, overridable via
     `BROWSER4_CLI_MIRROR_PREFERENCE_TTL_SECS`).

2. **Write**: `save_mirror_preference()` writes atomically via temp file +
   rename after concurrent speed tests complete and a best mirror is selected.

3. **Delete**: `delete_mirror_preference_cache()` is called when a download
   fails against the cached mirror, forcing a fresh speed test on retry.

### Defined in

`cli/browser4-cli/src/daemon.rs` — constant `MIRROR_PREFERENCE_CACHE_FILE`,
struct `MirrorPreference`, functions `load_mirror_preference()`,
`save_mirror_preference()`, `delete_mirror_preference_cache()`.

---

## 5. Download cache — archive + checksum

**Scope**: Per-version — one archive + one checksum sidecar per installed tag.

| Property | Value |
|---|---|
| **Paths** | `{cache}/downloads/{tag}/{asset_name}` and `{cache}/downloads/{tag}/{asset_name}.sha256` |
| **Format** | Binary (archive) + hex-encoded SHA-256 (sidecar) |
| **Created by** | `browser4-cli install`, `browser4-cli upgrade` |
| **Read by** | `browser4-cli install`, `browser4-cli upgrade` (cache-hit fast-path) |
| **Deleted by** | `browser4-cli uninstall` (when cache dir is removed), automatic eviction (keeps newest 3) |

### Example

```
{cache}/downloads/v4.11.0/
├── browser4-bundle-runtime-linux-x64.tar.gz
└── browser4-bundle-runtime-linux-x64.tar.gz.sha256
```

The `.sha256` sidecar contains the hex-encoded SHA-256 digest of the archive
(e.g. `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`).

### Lifecycle

1. **Cache write** (`try_cache_downloaded_archive()`): After a successful
   download, the archive is SHA-256 hashed, then both the archive and the
   checksum are written atomically (temp file → rename).

2. **Cache hit** (`try_restore_from_download_cache()`): When the requested tag
   is already cached, the archive is copied to the download location and its
   integrity is verified against the stored checksum.  A mismatch triggers
   automatic cleanup and re-download.  Missing sidecar or empty checksum also
   triggers cleanup.

3. **Eviction** (`evict_old_download_cache_entries()`): After a successful
   install, the cache directory is scanned for version-tag directories
   (`v*.*.*`).  Only the **3 newest** entries (by semver) are kept; older ones
   are removed.  Non-version directories (e.g. `latest`) are left untouched.

4. **`--force` bypass**: When `--force` is passed, the cache is bypassed and a
   fresh download is performed.

### Defined in

`cli/browser4-cli/src/daemon.rs` — functions `cached_download_path()`,
`cached_checksum_path()`, `try_cache_downloaded_archive()`,
`try_restore_from_download_cache()`, `evict_old_download_cache_entries()`.

---

## 6. `.install.lock/`

**Scope**: Singleton — advisory lock directory that serialises concurrent
install/upgrade operations.

| Property | Value |
|---|---|
| **Path** | `{data}/runtime/.install.lock/` |
| **Format** | Directory containing a single `pid` file |
| **Created by** | `browser4-cli install`, `browser4-cli upgrade` (on entry) |
| **Deleted by** | Dropped automatically when the `RuntimeInstallLock` guard goes out of scope (including on panic via `Drop`) |

### Lifecycle

1. **Acquire** (`RuntimeInstallLock::acquire()`): Uses atomic `mkdir` to create
   the lock directory.  If the directory already exists (another process holds
   the lock), the current process polls every **500 ms** until the directory is
   released or the timeout expires (default: **300 seconds**, constant
   `INSTALL_LOCK_TIMEOUT_SECS`).  On timeout the stale lock is removed and an
   error is returned.

2. **Hold**: While the lock is held, a `pid` file is written inside the lock
   directory for diagnostics.

3. **Release** (the `Drop` impl): `fs::remove_dir_all` removes the lock
   directory.  This also happens on panic, preventing orphaned locks from
   blocking future installs.

### Defined in

`cli/browser4-cli/src/daemon.rs` — constant `INSTALL_LOCK_DIR_NAME`, struct
`RuntimeInstallLock`, impl `Drop for RuntimeInstallLock`.

---

## 7. `.install-method`

**Scope**: Singleton — records how the `browser4-cli` binary itself was installed.

| Property | Value |
|---|---|
| **Path** | `cli/bin/.install-method` (inside the npm package) |
| **Format** | Plain text |
| **Content** | `npm` |
| **Created by** | `cli/scripts/postinstall.js` (npm `postinstall` lifecycle script) |
| **Read by** | None currently (informational / future use) |

### Defined in

`cli/bin/.install-method` — checked into the repository with content `npm`.

---

## 8. `cli-state.json` / `sessions/{name}.json`

**Scope**: Per-session CLI state — one default file + one per named session.

| Property | Value |
|---|---|
| **Paths** | `~/.browser4/cli-state.json` and `~/.browser4/sessions/{name}.json` |
| **Format** | JSON |
| **Overridable by** | `BROWSER4_CLI_STATE_DIR` env var |
| **Created by** | Various CLI commands that modify session state (`open`, `goto`, etc.) |
| **Read by** | All CLI commands that need the active session |
| **Deleted by** | `browser4-cli uninstall` (indirectly — the `~/.browser4/` directory may be removed) |

### Schema

```json
{
  "sessionId": "abc123",
  "baseUrl": "http://localhost:8182",
  "activeSelector": null,
  "sessionName": null,
  "lastMousePosition": {
    "x": 120.0,
    "y": 240.0
  }
}
```

| Field | Type | Description |
|---|---|---|
| `sessionId` | string\|null | Active session ID returned by the Browser4 server on `open`. |
| `baseUrl` | string | Base URL of the Browser4 REST server. Default: `"http://localhost:8182"`. |
| `activeSelector` | string\|null | Reserved selector slot for future CLI workflows. |
| `sessionName` | string\|null | Named session label from the `-s <name>` flag. |
| `lastMousePosition` | object\|null | Last known pointer coordinates, used to restore state across invocations. |

These files are not directly managed by `install`/`upgrade`/`uninstall`, but
`uninstall` may prompt to remove the `~/.browser4/` directory which contains
them. The `uninstall` command does **not** touch the `{data}` runtime directory
by default.

### Defined in

`cli/browser4-cli/src/state.rs` — struct `CliState`, functions `read_state()`,
`write_state()`, `clear_state()`, `clear_all_state()`.

---

## Summary: which command touches which file

| File | `install` | `upgrade` | `uninstall` | Standalone scripts |
|---|---|---|---|---|
| `browser4-installation.json` | **writes** | **writes** | deletes (via dir removal) | — |
| `current.tag` | **writes** | **writes** | deletes (via dir removal) | — |
| `mirrors.json` | reads | reads | — | — |
| `mirror-preference.json` | **writes** / reads | **writes** / reads | — | — |
| Download cache (`*.tar.gz` + `.sha256`) | **writes** / reads | **writes** / reads | deletes (via dir removal) | — |
| `.install.lock/` | **creates** / deletes | **creates** / deletes | — | — |
| `.install-method` | — | — | — | — (set by npm) |
| `cli-state.json` | — | — | deletes (via dir removal) | — |

---

## Related documentation

- [`cli-install-upgrade.md`](cli-install-upgrade.md) — runtime data directory layout, download mirrors, env var reference.
- [`cli-standalone-install.md`](cli-standalone-install.md) — standalone installer scripts for the CLI binary.
- [`cli/README.md`](../cli/README.md) — installation methods for the CLI binary.
