# Standalone CLI Installer Scripts

Download and install the `browser4-cli` native binary without npm, Cargo, or
Homebrew — just `curl` (or `Invoke-WebRequest`) and your OS.

Two platform scripts are provided:

| Script | Target | Requirements |
|---|---|---|
| `install-browser4-cli.ps1` | Windows | PowerShell 5.1+ |
| `install-browser4-cli.sh` | Linux / macOS / Git Bash | bash 3.2+, curl |

Both scripts detect your OS, CPU architecture, and (on Linux) libc variant,
then download the matching pre-built native binary from the first reachable
source.

---

## Quick start

### Windows (PowerShell)

```powershell
# Download and run (latest version, default location, adds to PATH)
Invoke-WebRequest -Uri "https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1" -OutFile "$env:TEMP\install-browser4-cli.ps1"
powershell -ExecutionPolicy Bypass -File "$env:TEMP\install-browser4-cli.ps1"
```

Or, if you've cloned the repo:

```powershell
powershell -ExecutionPolicy Bypass -File cli\scripts\install-browser4-cli.ps1
```

### Linux / macOS (bash)

```bash
# Pipe from the OSS mirror
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash

# Or run locally after cloning
./cli/scripts/install-browser4-cli.sh
```

---

## What the scripts do

1. **Detect** your platform and map it to the correct binary asset name.
2. **Download** from GitHub Releases (primary) or Alibaba Cloud OSS (fallback).
3. **Install** the binary to a default directory (or a custom one you provide).
4. **Add to PATH** — on Windows, updates the user `PATH` registry; on Unix,
   appends an `export PATH` line to your shell rc file.
5. **Verify** the binary can run `--version`.

No admin/sudo is required for the default install locations. The scripts never
modify system state outside the chosen install directory and your shell
profile.

---

## Binary asset names

The scripts select the correct pre-built binary for your platform:

| OS | Architecture | Asset name |
|---|---|---|
| Linux (glibc) | x86\_64 | `browser4-cli-linux-x64` |
| Linux (glibc) | ARM64 | `browser4-cli-linux-arm64` |
| Linux (musl) | x86\_64 | `browser4-cli-linux-musl-x64` |
| Linux (musl) | ARM64 | `browser4-cli-linux-musl-arm64` |
| Windows | x86\_64 | `browser4-cli-win32-x64.exe` |
| macOS | x86\_64 | `browser4-cli-darwin-x64` |
| macOS | ARM64 (Apple Silicon) | `browser4-cli-darwin-arm64` |

---

## Download sources

The scripts try sources in order and use the **first reachable** one:

| Priority | Name | URL pattern |
|---|---|---|
| 1 | GitHub Releases | `https://github.com/platonai/Browser4/releases/latest/download/{binary}` |
| 2 | Alibaba Cloud OSS | `https://browser4.oss-cn-beijing.aliyuncs.com/releases/download/latest/{binary}` |

When `--version` (or `-v`) is passed, `latest` in the URLs above is replaced
with the specific tag you provide.

Use `--source github` or `--source oss` to force a single source and skip the
fallback.

---

## Options reference

### PowerShell (`install-browser4-cli.ps1`)

| Parameter | Type | Default | Description |
|---|---|---|---|
| `-Version` | `string` | `""` (latest) | Specific release tag, e.g. `"v4.11.0"` or `"v0.1.12-cli"`. |
| `-InstallDir` | `string` | `$env:LOCALAPPDATA\Programs\browser4-cli` | Directory to place the binary. |
| `-Source` | `"github"` / `"oss"` | `""` (try both) | Force a single download source. |
| `-AddToPath` | `bool` | `$true` | Whether to append the install directory to the user `PATH`. |
| `-Silent` | `switch` | off | Suppress all non-error output. |
| `-DryRun` | `switch` | off | Print what would be done without making changes. |

### Bash (`install-browser4-cli.sh`)

| Option | Description |
|---|---|
| `--version`, `-v TAG` | Specific release tag. Default: latest. |
| `--install-dir`, `-d DIR` | Install directory. Default: `~/.local/bin` (falls back to `~/bin` if it exists). |
| `--source SRC` | Force source: `github` or `oss`. Default: try both. |
| `--no-path` | Skip adding the install directory to your shell rc file. |
| `--silent`, `-s` | Suppress non-error output. |
| `--dry-run` | Print what would be done without making changes. |
| `--help`, `-h` | Show usage. |

---

## Install locations

### Defaults

| Platform | Default install directory |
|---|---|
| Windows | `%LOCALAPPDATA%\Programs\browser4-cli\` |
| Linux | `~/.local/bin/` (or `~/bin/` if it already exists) |
| macOS | `~/.local/bin/` |

Override with `-InstallDir` (PowerShell) or `--install-dir` / `-d` (bash).

### System-wide install (bash only — requires sudo)

```bash
sudo ./install-browser4-cli.sh --install-dir /usr/local/bin
```

---

## PATH setup

### Windows

The PowerShell script writes the install directory to the **user** `PATH`
registry value (`HKCU\Environment\Path`). The change takes effect in new
terminal windows immediately. To use it in the current terminal without
restarting:

```powershell
$env:Path = [System.Environment]::GetEnvironmentVariable('Path','User') + ';' + [System.Environment]::GetEnvironmentVariable('Path','Machine')
```

### Linux / macOS

The bash script appends an `export PATH` line to the first shell rc file it
finds (checked in order: `.zshrc` → `.bashrc` → `.bash_profile` → `.profile`).
To apply immediately in the current shell:

```bash
export PATH="$HOME/.local/bin:$PATH"
# or, if using the rc file:
source ~/.bashrc
```

Pass `--no-path` (or `-AddToPath:$false` on PowerShell) to skip PATH
modification entirely.

---

## Examples

### Silent CI / Docker installs

```bash
# Bash — silent, specific version, no PATH modification
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash -s -- --silent --no-path --version v4.11.0
```

```powershell
# PowerShell — silent, specific version, custom directory
powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Silent -Version "v4.11.0" -InstallDir "C:\tools\browser4"
```

### Force a specific download mirror

```bash
# Download from OSS only (skip GitHub entirely)
./install-browser4-cli.sh --source oss
```

```powershell
# Download from GitHub only
powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Source github
```

### Dry-run to inspect before installing

```bash
./install-browser4-cli.sh --dry-run
```

```powershell
powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -DryRun
```

### Install a specific pre-release tag

```bash
./install-browser4-cli.sh --version v0.1.12-cli
```

---

## Upgrade

To upgrade to a newer version, run the installer again. It will overwrite the
existing binary with the latest (or the `--version` you specify).

---

## Uninstall

Delete the installed binary:

```bash
# Bash (default location)
rm ~/.local/bin/browser4-cli-*
```

```powershell
# PowerShell (default location)
Remove-Item "$env:LOCALAPPDATA\Programs\browser4-cli\browser4-cli-*"
```

Remove the PATH entry you added during install:
- **Windows**: Edit the `Path` variable under `HKCU\Environment` via System
  Properties → Environment Variables.
- **Linux/macOS**: Remove the `export PATH="..."` line from your shell rc file.

---

## How this differs from `browser4-cli install`

| | Standalone installer scripts | `browser4-cli install` command |
|---|---|---|
| **Installs what** | The `browser4-cli` binary itself | The Browser4 *runtime bundle* (JRE, JARs) |
| **Runs before** | You have `browser4-cli` | You already have `browser4-cli` |
| **Downloads** | ~10-20 MB native binary | ~200 MB runtime archive |
| **Purpose** | Bootstrap the CLI tool | Set up the backend server |

Once the CLI is installed via these scripts, use `browser4-cli install` to
download the runtime bundle, or just run `browser4-cli open <url>` — it
auto-installs the runtime on first use.

---

## Environment variables

| Variable | Purpose |
|---|---|
| `GITHUB_TOKEN` | Authenticate GitHub API requests (avoids rate limits for frequent installs). |

The install scripts are standalone shell/PowerShell programs — they do not use
the CLI's mirror configuration, proxy settings, or runtime directory variables.
If you need proxy support for the download, configure it at the shell level:

```bash
# Bash proxy
export https_proxy=http://proxy.example.com:8080
./install-browser4-cli.sh
```

```powershell
# PowerShell proxy
$env:HTTPS_PROXY = "http://proxy.example.com:8080"
.\install-browser4-cli.ps1
```

---

## Supported platforms

| OS | Architectures |
|---|---|
| Windows 10+ | x86\_64 |
| Windows (Git Bash / MSYS2) | x86\_64 |
| Linux (glibc 2.17+) | x86\_64, ARM64 |
| Linux (musl 1.2+) | x86\_64, ARM64 |
| macOS 11+ | x86\_64, ARM64 (Apple Silicon) |
