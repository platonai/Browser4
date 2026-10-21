---
title: "Plugins"
description: "First-party Browser4 plugins: which ones a dev runtime bundle already ships, which need an explicit install, the install/restart workflow, how plugin tool names are discovered, and the failure modes — opt-in plugins, stale manifest versions, plugins lost across a rebuild."
tier: procedure
---

# Plugins

Plugins are server-side JARs that add MCP tools (and occasionally browse
handlers or summary algorithms) to the backend. They are loaded from the
runtime bundle's `plugins/` directory at server startup, so the plugin set is a
property of the running backend — not of the CLI process.

## Quick Start

```bash
browser4-cli plugin list                       # installed JARs + load status
browser4-cli plugin image detectImages --json '{"minWidth": 200}'   # dynamic plugin command
browser4-cli tool call image_detect_images --json '{"minWidth": 200}'   # same tool, generic form
```

Install a plugin the bundle does not ship, then let the backend pick it up:

```bash
# From a source checkout (PowerShell) — the VERSION lookup keeps the JAR name in step with the build:
./b4w.ps1 plugin install "browser4-plugins/browser4-pptx/target/browser4-pptx-$(Get-Content VERSION).jar"

# From a source checkout (bash):
browser4-cli plugin install "browser4-plugins/browser4-pptx/target/browser4-pptx-$(cat VERSION).jar"

browser4-cli stop        # the next CLI command auto-restarts the dev backend
browser4-cli plugin list # the plugin now reports a status; its tools appear
```

`plugin install` takes **exactly one** JAR. A `*` glob is expanded by the CLI
(PowerShell does not expand wildcards for native commands, bash does) and, when
several JARs match, the one whose name carries this CLI's version is installed
and the choice is reported on stderr. If nothing matches the CLI version, the
command fails listing every candidate — pass one explicit file name instead.

## When to Use

| You need | Plugin | Bundled in a dev bundle? |
|---|---|---|
| Image detection / bulk download | `browser4-images` | yes |
| Page → Markdown conversion, link discovery, site crawl to `.md` | `browser4-markdown` | yes |
| Video detection, media download, FFmpeg processing | `browser4-media` | yes |
| SEO metadata extraction / issue audit | `browser4-seo` | yes, but opt-in (see below) |
| Async X-SQL/URL scraping backend (`/api/swarm/**`, `swarm` command) | `browser4-swarm` | yes |
| Page → PowerPoint deck | `browser4-pptx` | no — install on demand |
| CAPTCHA detect/solve | `browser4-captcha` | no — install on demand (external service) |
| Bookmark/history/cookie import from a system browser | `browser4-profile-import` | no — install on demand |
| Demo summary algorithm | `browser4-summary-demo` | no |

## How It Works

- **Load path.** The backend starts with `lib/*:plugins/*` on its classpath and
  scans every JAR in `plugins/` for `META-INF/browser4-plugin.json` plus its
  Spring auto-configuration. Adding or removing a JAR therefore needs a
  restart; the CLI detects the changed `plugins/` directory and warm-restarts a
  backend it started itself (see [development-mode.md](development-mode.md) and
  `docs/cli-install-upgrade.md`).
- **What a dev bundle ships.** `build-runtime-bundle.ps1` bundles the
  first-party plugins this checkout builds — `browser4-swarm`,
  `browser4-images`, `browser4-markdown`, `browser4-media`, `browser4-seo` —
  taking each JAR from the Maven local repository and refusing any JAR whose
  version differs from the checked-out project version. Run
  `mvn install -pl browser4-plugins/<module> -DskipTests` if a plugin is reported
  missing.
- **What it deliberately does not ship.** `browser4-captcha` (needs an external
  CAPTCHA-solving service), `browser4-pptx` (shaded JAR that embeds Apache POI),
  `browser4-profile-import` (needs `dd-plist`, which the bundle's `lib/` does not
  carry) and `browser4-summary-demo` (demo). The build names each one — with the
  exact `plugin install` command — at the end of its output and in
  `runtime-bundle.json` (`availablePlugins`).
- **Installed plugins survive a rebuild.** `plugins/` lives inside the bundle's
  build-output tree, which a rebuild resets; the build snapshots the JARs found
  there first and carries them over afterwards (a JAR belonging to a plugin the
  build itself bundles is dropped, newest copy wins), so a `plugin install` is
  not undone by the next bundle build. The carried-over JARs are reported
  (`Preserved installed plugin(s): …`) and recorded in `runtime-bundle.json`
  (`preservedPlugins`).
- **Opt-in plugins.** A manifest with `defaultEnabled: false` (today:
  `browser4-seo`) is skipped by the load policy even when its JAR is present.
  `browser4-cli plugin list` / `GET /api/plugins` show it as installed but
  disabled. Enable it for the backend with
  `-Dbrowser4.plugins.enable=browser4-seo` (or `BROWSER4_PLUGINS_ENABLE=browser4-seo`,
  or `browser4.plugins.enable-all=true`).
- **`plugin list` does not list tools.** It lists installed JARs with their file
  name, manifest version, SDK version, description and load status. Tool names
  come from `tool list` (or `GET /mcp/tools`, a bare name array), from
  `plugin <domain> <method> --help`, from a tool call that fails on a missing
  argument (the error prints the full signature), or from the tables below.
- **Version identity.** The version column is read from the JAR's
  `META-INF/browser4-plugin.json`. When that `version` is a hard-coded literal
  instead of `${project.version}`, it drifts from the artifact version and the
  loaded build becomes unidentifiable. `bin/verify-plugin.ps1 <jar>` reports the
  skew (`-Strict` makes it a failure) — see [Errors & Recovery](#errors--recovery).

## Patterns

Tool names are the snake_case form of `domain.method`:
`media.detectVideos` → `media_detect_videos`, `markdown.discoverLinks` →
`markdown_discover_links`. Both spellings work: `tool call media_detect_videos`
and the dynamic command `plugin media detectVideos`.

| Domain | Methods (`domain.method`) | CLI examples |
|---|---|---|
| `image` | `detectImages`, `download`, `downloadAll`, `downloadBatch` | `browser4-cli tool call image_detect_images --json '{"minWidth":100,"minHeight":60}'`; `tool call image_download --json '{"url":"…","outputPath":"…"}'` |
| `markdown` | `convert`, `crawl`, `crawlFrom`, `fetch`, `discoverLinks`, `read` | `tool call markdown_convert --json '{"outputPath":"…"}'`; `tool call markdown_fetch --json '{"url":"https://httpbin.org/html"}'` |
| `media` | `detectVideos`, `download`, `process`, `extractAudio`, `trim`, `compress`, `getInfo` | `tool call media_detect_videos`; `tool call media_get_info --json '{"filePath":"…"}'` |
| `pptx` | `generate` | `tool call pptx_generate --json '{"outputDir":"…"}' --timeout 600` |
| `seo` | `extractMeta`, `checkIssues` | `tool call seo_extract_meta` (opt-in plugin) |
| `captcha` | `detect`, `solve`, `solveImage`, `getBalance` | `tool call captcha_detect`; `tool call captcha_get_balance` |
| `swarm` | `submit`, `query`, `status`, `result`, `batchStatus` | usually wrapped by `browser4-cli swarm submit …` |
| `profile_import` | `list_sources`, `import` | exposed as the named CLI commands `browser4-cli profile sources` / `browser4-cli profile import` |

Long-running plugin tools (`pptx_generate`, `media_process`, `image_download_all`,
`markdown_crawl`) need a raised client timeout: `--timeout 600`. The CLI's
default tool-call timeout is shorter than the plugins' own download timeouts, so
the client can give up while the backend is still working.

## Flags

| Command | Purpose | Notes |
|---|---|---|
| `plugin list` | Installed plugin JARs, versions, load status | No tool names — see [How It Works](#how-it-works) |
| `plugin info <name>` | Details for one installed plugin | Manifest name, JAR file name, or the short name (e.g. `captcha` for `browser4-captcha-<version>.jar`) |
| `plugin install <file> [--replace]` | Install a JAR from disk | One JAR per call; a `*` glob is expanded by the CLI and the version-matched JAR is chosen; `--replace` overwrites an existing install |
| `plugin remove <name> [-y\|--yes]` | Remove an installed plugin | Needs a restart (the CLI warm-restarts its own backend) |
| `plugin <domain> <method> [--flag value]` | Dynamic plugin command | Resolved from the backend's tool registry at dispatch time |
| `plugin [<domain>] --help` | Domain method list (`plugin markdown --help`, `--help plugin-markdown`) | Lists the domain's methods and the runnable invocation forms |
| `plugin` (bare) | Plugin tool domains with their methods | Same data as `tool list`, grouped by domain |
| `tool list [--filter <substr>]` | Every callable MCP tool name | Read-only; no session needed |
| `tool call <mcp-name> [--json '{…}'] [--fail-on-error]` | Generic passthrough to any MCP tool | The reliable form for smoke tests; `--fail-on-error` exits non-zero when the result carries `success:false` or an `error` (the payload stays on stdout either way) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| `tool call image_detect_images` fails as an unknown tool; `plugin list` has no `browser4-images` | Plugin not installed in this backend | `plugin install "browser4-plugins/browser4-images/target/browser4-images-<version>.jar"`, then `browser4-cli stop` (the next command restarts the backend) |
| `plugin list` shows the plugin, its tools are still unknown | Opt-in plugin (`defaultEnabled: false`) not enabled, or the plugin failed to load | Enable it with `browser4.plugins.enable=<name>`; check the server log (`browser4-cli doctor log`) for a bean-creation error |
| A tool name is unknown but the plugin is listed | The name has the wrong spelling, or you are reading it off `plugin list` (which has no tool names) | `tool list` for names, `plugin <domain> --help` for one domain's methods, `plugin <domain> <method> --help` for one tool's arguments |
| `plugin install` fails on a wildcard path | Several JARs matched and none carries this CLI's version | Pass one explicit file name, resolved from the checkout version (`browser4-plugins/<module>/target/<module>-<version>.jar`) |
| `plugin list` shows version `4.12.0-rc.1` for a `4.14.1-SNAPSHOT` JAR | The plugin manifest's `version` is a hard-coded literal instead of the Maven project version | Rebuild the module after the manifest is filtered; detect the skew with `bin/verify-plugin.ps1 <jar>` (`-Strict` for a hard failure) |
| A plugin installed yesterday is gone from `plugin list` | A bundle rebuild used to reset the whole build-output tree, `plugins/` included | Current builds snapshot and restore `plugins/` JARs; if it still happens, reinstall and note the dropped-JAR lines in the build output |
| A plugin tool reports `[MISSING_REQUIRED_ARG]` | Argument names differ per tool | The error prints the tool signature (name and arguments) — read it, then retry with `--json` |
