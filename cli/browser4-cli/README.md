# Browser4 CLI

A command-line interface for controlling a [Browser4](https://github.com/platonai/Browser4) server. Designed for use by AI agents through SKILLS + CLI.

## Build

```bash
cd cli/browser4-cli
cargo build --release
# Binary is at target/release/browser4-cli
# Or install to Cargo bin directory (%USERPROFILE%\.cargo\bin on Windows, ~/.cargo/bin on Unix):
cargo install --path .
```

Or run directly:

```bash
cargo run -- <command> [args] [options]
```

## Usage

```
browser4-cli <command> [args] [options]
browser4-cli -s=<session> <command> [args] [options]
```

### Global options

| Flag | Description |
|---|---|
| `--help [command]` | Print help (optionally for a specific command) |
| `--version` | Print version |
| `-s=<name>` | Named session label |
| `--server=<url>` | Override Browser4 server URL |

Sessions are persisted independently per name. Omitting `-s` uses the
default session (`~/.browser4/cli-state.json`). With `-s=<name>`, a
separate state file is stored under `~/.browser4/sessions/<name>.json`.
`open` without `-s` reuses the default session if one exists; with
`-s=<name>` it switches to or creates the named session.

## Install Browser4 backend runtime

`browser4-cli install` downloads a self-contained Browser4 runtime bundle from GitHub Releases.
The bundle includes both `Browser4.jar` and a bundled minimal `jlink` JRE under the CLI state
directory, so later `browser4-cli open` invocations can launch Browser4 without relying on a
system-wide Java installation.

```bash
# install the latest runtime bundle for the current OS/architecture
browser4-cli install

# install a specific release tag
browser4-cli install --tag=v4.9.3

# force a tagged reinstall
browser4-cli install --tag=4.9.3 --force

# after installation, open uses the bundled JRE automatically
browser4-cli open
```

## Testing

```bash
## Run all tests (unit + end-to-end):
cargo test

## Run only the end-to-end tests and print their output:
cargo test --test e2e -- --nocapture

## Run a specific end-to-end test scenario:
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_form_submission
```

## License

Apache-2.0
