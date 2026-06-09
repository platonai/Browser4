# browser4-cli: `install` and `upgrade` User Guide

## Overview

The `install` and `upgrade` commands manage the **Browser4 runtime bundle** — a
self-contained distribution that includes all dependency JARs, a minimal
`jlink`-built JRE, and platform-specific launcher scripts.  The runtime bundle
is required to start the Browser4 backend server; the CLI downloads and
installs it automatically on first use, but you can also manage it explicitly.

---

## `install` — Install the runtime bundle

```bash
browser4-cli install [--tag=<version>] [--force]
```

### What it does

1. Detects your platform (OS + architecture).
2. Downloads the matching runtime bundle archive (~200 MB) from the first
   reachable download mirror.
3. Extracts the archive into a versioned directory under the runtime data
   directory.
4. Writes a `current.tag` marker so the CLI knows which version is active.
5. Checks that Google Chrome / Chromium is available (auto-installs Chrome on
   Debian/Ubuntu, RHEL/Fedora, or Windows when possible).

### Options

| Option | Description |
|---|---|
| `--tag=<version>` | Install a specific release version (e.g. `--tag=v4.11.0` or `--tag=4.11.0`). The `v` prefix is optional and normalised automatically. Defaults to the **latest** release. |
| `--force` | Re-download and re-install even if the requested version is already present in the local cache. Without `--force`, an already-installed version is reused. |

### Examples

```bash
# Install the latest release
browser4-cli install

# Install a specific version
browser4-cli install --tag=v4.11.0

# Re-install a specific version (force re-download)
browser4-cli install --tag=4.11.0 --force
```

### Output

```
Browser4 runtime installed successfully.
- Tag: v4.11.0
- Asset: browser4-bundle-runtime-linux-x64.tar.gz
- Install dir: /home/user/.local/share/browser4/runtime/v4.11.0
- Lib dir: /home/user/.local/share/browser4/runtime/v4.11.0/lib
- Java: /home/user/.local/share/browser4/runtime/v4.11.0/runtime/bin/java
- Source: https://github.com/platonai/Browser4/releases/download/v4.11.0/browser4-bundle-runtime-linux-x64.tar.gz
```

If the version is already installed (without `--force`):

```
Browser4 runtime already installed.
- Tag: v4.11.0
- Asset: browser4-bundle-runtime-linux-x64.tar.gz
...
```

---

## `upgrade` — Upgrade to a newer version

```bash
browser4-cli upgrade [<tag>] [--force]
```

### What it does

`upgrade` is a convenience wrapper around `install`.  It downloads and installs
the requested version (or the latest), then prints a reminder to restart the
server so the new runtime takes effect.

When the requested version is **already** installed, `upgrade` skips the
download and reports that Browser4 is already at that version — unless
`--force` is passed.

### Options

| Option | Description |
|---|---|
| `<tag>` | Positional argument for the target version.  Accepts the same forms as `--tag` on `install` (e.g. `v4.11.0` or `4.11.0`).  Defaults to the **latest** release. |
| `--force` | Force re-download even if the requested version is already installed. |

### Examples

```bash
# Upgrade to the latest release
browser4-cli upgrade

# Upgrade to a specific version
browser4-cli upgrade v4.11.0

# Force re-download of the latest (even if already on latest)
browser4-cli upgrade --force
```

### Output

```
Upgrading Browser4 runtime...
Browser4 upgraded successfully to v4.11.0.
- Install dir: /home/user/.local/share/browser4/runtime/v4.11.0
- Lib dir: /home/user/.local/share/browser4/runtime/v4.11.0/lib
- Java: /home/user/.local/share/browser4/runtime/v4.11.0/runtime/bin/java
Restart the server to use the new version: browser4-cli stop && browser4-cli open <url>
```

If already at the requested version (without `--force`):

```
Browser4 is already at the latest version (v4.11.0).
```

---

## Auto-install on first use

When you run any command that requires the Browser4 backend (e.g. `open`,
`goto`), the CLI automatically checks whether a runtime bundle is installed.
If none is found and the current directory is not a Browser4 repository
checkout, it runs the equivalent of `browser4-cli install` automatically.

```
$ browser4-cli open https://example.com
Browser4 server not running. Starting...
Downloading Browser4 runtime bundle from https://github.com/platonai/...
Downloaded 213456789 bytes for Browser4 runtime bundle.
Starting server from Browser4 runtime at .../v4.11.0 using .../bin/java on port 8182...
```

You can skip the local-repo auto-build detection by setting
`BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`.

---

## Download mirrors

The CLI probes download mirrors in order and uses the **first reachable** one.

### Built-in defaults (used when no config file exists)

| Priority | Name | Base URL |
|---|---|---|
| 1 | `github` | `https://github.com/platonai/Browser4/releases` |
| 2 | `aliyun-oss` | `https://web-insight.oss-cn-beijing.aliyuncs.com/releases` |

### Custom mirror configuration

Create a JSON file at `{runtime-data-dir}/mirrors.json` (or set
`BROWSER4_MIRRORS_CONFIG` to a custom path):

```json
{
  "mirrors": [
    {
      "name": "corporate-cdn",
      "base_url": "https://artifacts.internal.example.com/releases"
    },
    {
      "name": "aliyun-oss",
      "base_url": "https://web-insight.oss-cn-beijing.aliyuncs.com/releases"
    },
    {
      "name": "github",
      "base_url": "https://github.com/platonai/Browser4/releases"
    }
  ]
}
```

- Mirrors are tried **in array order**.
- Each mirror is probed with a fast TCP connect to `<host>:443` (5 s timeout,
  overridable via `BROWSER4_CLI_MIRROR_CHECK_TIMEOUT_SECS`).
- If **no** mirror is reachable, the CLI falls back to the first mirror and
  attempts the download anyway (so you get a clear HTTP error rather than a
  confusing "no mirrors" message).
- Mirror names appear in log messages and error output to help you identify
  which source was used.

### Legacy single-source override

Setting `BROWSER4_RELEASES_BASE_URL` completely bypasses the mirror system:

```bash
BROWSER4_RELEASES_BASE_URL=https://custom.example.com/releases browser4-cli install
```

This is equivalent to having a single-entry mirror list with name `custom`.

---

## Runtime data directory layout

```
{runtime-data-dir}/                     Platform-conventional data directory
├── runtime/                            Versioned installs
│   ├── current.tag                     Plain-text file: "v4.11.0"
│   ├── v4.10.0/
│   │   ├── lib/                        Dependency JARs
│   │   │   ├── Browser4Bundle.jar
│   │   │   └── ...
│   │   ├── runtime/                    Bundled JRE (jlink)
│   │   │   └── bin/java
│   │   ├── bin/                        Launcher scripts
│   │   └── browser4-installation.json  Install metadata
│   └── v4.11.0/
│       └── ...
└── downloads/                          Download cache (keeps 3 newest)
    ├── v4.10.0/
    │   └── browser4-bundle-runtime-linux-x64.tar.gz
    │   └── browser4-bundle-runtime-linux-x64.tar.gz.sha256
    └── v4.11.0/
        └── ...
```

### Platform-specific paths

| Platform | Runtime data dir |
|---|---|
| Linux | `$XDG_DATA_HOME/browser4/` (~ `~/.local/share/browser4/`) |
| macOS | `~/Library/Application Support/browser4/` |
| Windows | `%APPDATA%/browser4/` |

Override with `BROWSER4_RUNTIME_DIR`.

### Download cache

Repeated installs of the same version skip the network entirely — the
previously-downloaded archive is restored from the local download cache and its
SHA-256 checksum is verified before extraction.  The cache keeps the **3
newest** versioned entries; older ones are evicted automatically.

Use `--force` to bypass the cache and fetch a fresh download.

---

## Supported platforms

| OS | Architecture | Asset name |
|---|---|---|
| Windows | x86_64 | `browser4-bundle-runtime-windows-x64.zip` |
| Linux | x86_64 | `browser4-bundle-runtime-linux-x64.tar.gz` |
| macOS | x86_64 | `browser4-bundle-runtime-darwin-x64.tar.gz` |
| macOS | ARM64 (Apple Silicon) | `browser4-bundle-runtime-darwin-arm64.tar.gz` |

If your platform is not listed, the CLI prints an error with instructions to
install Java 17+ and use the standalone `Browser4.jar` release asset instead.

---

## Proxy support

The CLI automatically detects HTTP/HTTPS proxies from (in order):

1. `BROWSER4_CLI_PROXY` — explicit CLI override (set by `--proxy=<url>`)
2. `https_proxy` / `HTTPS_PROXY` / `http_proxy` / `HTTP_PROXY` / `all_proxy` / `ALL_PROXY`
3. Windows system proxy (WinHTTP / Internet Options)

On Windows, if the native TLS stack (`reqwest`) fails, the download falls back
to PowerShell's `Invoke-WebRequest` which uses the WinINET proxy stack.

---

## Environment variable reference

| Variable | Purpose |
|---|---|
| `BROWSER4_RUNTIME_DIR` | Override the runtime data directory |
| `BROWSER4_CLI_STATE_DIR` | Override the CLI session state directory |
| `BROWSER4_RELEASES_BASE_URL` | Single-source download URL (bypasses mirrors) |
| `BROWSER4_MIRRORS_CONFIG` | Path to custom `mirrors.json` |
| `BROWSER4_CLI_MIRROR_CHECK_TIMEOUT_SECS` | Mirror reachability timeout in seconds (default: `5`) |
| `BROWSER4_CLI_PROXY` | Explicit download proxy URL |
| `BROWSER4_CLI_FORCE_REMOTE_BUNDLE` | Skip local repo build; always download (`1`/`true`/`yes`/`on`) |
| `BROWSER4_CLI_HTTP_TIMEOUT_SECS` | HTTP request timeout in seconds (default: `30`) |
| `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` | Navigation request timeout in seconds (default: `120`) |

---

## Related commands

| Command | Purpose |
|---|---|
| `browser4-cli uninstall` | Remove all installed runtimes, download cache, and CLI state |
| `browser4-cli status` | Show the active runtime version and server health |
| `browser4-cli stop` | Gracefully stop the Browser4 server |
| `browser4-cli kill-all` | Forcefully stop the server and all spawned browsers |
