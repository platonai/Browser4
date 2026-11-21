# 🤖 Browser4

<p align="center">
  <a href="https://github.com/platonai/Browser4/actions/workflows/ci.yml"><img alt="CI build" src="https://img.shields.io/github/actions/workflow/status/platonai/Browser4/ci.yml?branch=main&style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4/releases"><img alt="Release" src="https://img.shields.io/github/v/release/platonai/Browser4?style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4"><img alt="Stars" src="https://img.shields.io/github/stars/platonai/Browser4?style=flat-square"></a>
  <a href="https://central.sonatype.com/artifact/ai.platon.pulsar/browser4-core"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/ai.platon.pulsar/browser4-core?style=flat-square"></a>
  <a href="https://www.npmjs.com/package/browser4-cli"><img alt="npm" src="https://img.shields.io/npm/v/browser4-cli?style=flat-square"></a>
  <a href="https://browser4.io"><img alt="Website" src="https://img.shields.io/website?url=https%3A%2F%2Fbrowser4.io&style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4"><img alt="Top language" src="https://img.shields.io/github/languages/top/platonai/Browser4?style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/badge/license-APACHE2-green?style=flat-square"></a>
</p>

---

English | [简体中文](README.zh.md) | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

> ⭐ **Finding Browser4 useful?** Run `browser4-cli star` — with the GitHub CLI signed in it stars this repository as you after a single confirmation (`--gh` skips the prompt); otherwise it opens the repository in the browser you are already logged into and leaves the click to you.

<!-- TOC -->
**Table of Contents**
- [🤖 Browser4](#-browser4)
  - [🌟 Introduction](#-introduction)
    - [✨ Key Capabilities](#-key-capabilities)
  - [Quick Start](#quick-start)
  - [DeepSeek Harness integration](#deepseek-harness-integration)
  - [🧭 Tool Selection Guide](#-tool-selection-guide)
    - [How to Interact with a Page](#how-to-interact-with-a-page)
    - [How to Extract Data](#how-to-extract-data)
    - [How to Process at Scale](#how-to-process-at-scale)
    - [How to Turn HTML into Spreadsheets — Zero LLM Tokens](#how-to-turn-html-into-spreadsheets--zero-llm-tokens)
  - [📦 Installation](#-installation)
  - [💡 CLI Guide for Humans](#-cli-guide-for-humans)
    - [Quick start](#quick-start-1)
    - [Mental model](#mental-model)
    - [Global options](#global-options)
    - [Key concepts before the command list](#key-concepts-before-the-command-list)
    - [Command cheat sheet](#command-cheat-sheet)
    - [Timeout environment variables](#timeout-environment-variables)
    - [State persistence](#state-persistence)
  - [🚀 Build from Source](#-build-from-source)
  - [Architecture](#architecture)
  - [📦 Modules Overview](#-modules-overview)
  - [🧩 Programming-Agent Kernel (browser4-coding)](#-programming-agent-kernel-browser4-coding)
  - [🧪 Test Fixture Server (MockSite)](#-test-fixture-server-mocksite)
  - [🤝 Support & Community](#-support--community)
  - [📜 Documentation](#-documentation)
  - [🔧 Proxy Configuration](#-proxy-configuration---unblock-website-access)
  - [License](#license)
  - [Links](#links)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4 — an AI-native browser engine for autonomous agents, intelligent extraction, and large-scale web automation.** 💖

### ✨ Key Capabilities

* 🤖 **Agent Browser** — AI agents and humans drive real browsers via a Rust CLI, MCP, and an agentic backend: navigate, click, fill, snapshot, batch, and loop.
* 🧬 **Zero-Token Extraction** — X-SQL + CSS selectors for deterministic extraction from live pages or stored HTML snapshots; WebMiner ML clustering turns HTML corpora into spreadsheet and report views with no LLM tokens.
* 🧠 **Hybrid Intelligence** — Combine LLM extraction, ML clustering, X-SQL, and a progressive experience store that reuses learned selectors and blockers.
* ⚡ **High-Performance Runtime** — Coroutine-safe, CDP-native engine designed for 100k–200k complex page visits per machine per day via swarm/crawl scale-out.
* 📦 **Enterprise-Scale Automation** — Swarm crawling, batch/loop jobs, stateful sessions, plugins, runtime skills, browser extension, and an opt-in MCP-over-HTTP endpoint.
* 🛠️ **Programming-Agent Kernel** — 50+ `coding.*` tools (sandboxed shell/fs, scaffolding, validation, self-development) for agents building Browser4 artifacts — or Browser4 itself.

## Quick Start

Paste the following instruction to your favorite AI agent like dsh, claude, codex, workbuddy or openclaw and run it:

```
Read https://browser4.io/SKILL.md, install or upgrade browser4-cli for browser automation, perform the following task:

1. Open the browser in headed mode (`open --headed`) so the window is visible — this is a human-facing demo
2. go to amazon.com
3. search for pens to draw on whiteboards
4. compare the first 4 ones
5. write the result to a markdown file
```

`https://browser4.io/SKILL.md` is the published copy of
[skills/browser4-cli/SKILL.md](skills/browser4-cli/SKILL.md) in this repository. If you already
have the checkout, point the agent at that local path instead so it reads the version matching
your code.

## DeepSeek Harness integration

https://github.com/platonai/dsh-browser4

```
dsh plugin --profile web add dsh-browser4                  # npm registry
dsh plugin --profile web add github:platonai/dsh-browser4  # GitHub
```

## 🧭 Tool Selection Guide

Choosing the right tool for your task:

### How to Interact with a Page

Use `snapshot -i --boxes` to see clickable/typeable elements with refs like `e15`, then `click <ref>`, `fill <ref> "<text>"`, `type`/`press`, `select`, `hover`/`drag`/`scroll`, and `wait` to drive the page. All interaction commands accept CSS selectors too. Chain multiple steps with `batch` — every step runs on the client through the same path as the equivalent standalone command.

Content embedded in `<iframe>`s (payment forms, editors, widgets) is reached with the built-in frame switching: `frames` lists the frame tree, `frame "<iframe selector>"` scopes subsequent element commands into that frame (same-origin iframes fully supported), and `frame main` returns to the main document — no manual `contentDocument` eval needed.

Typical interactive flow:

```bash
# Humans usually want to see the browser — open it headed
browser4-cli open --headed https://example.com/login
browser4-cli snapshot -i --boxes
browser4-cli fill e3 "user@example.com"
browser4-cli fill e4 "secret" --submit
browser4-cli wait --load networkidle
browser4-cli snapshot -i
# iframe-heavy page:
browser4-cli frame "#pay-frame"
browser4-cli fill "#card-number" "4111 1111 1111 1111"
browser4-cli frame main
```

### How to Extract Data

```
Need to extract data from a page?
├─ Interactive page (click, fill, scroll first)? → snapshot + refs, then extract
├─ Static page, one field? → htmlsnapshot get text "<selector>"
├─ Static page, all matches of one field? → htmlsnapshot get all text "<selector>"
├─ Static page, multiple correlated fields (title+price+url per item)?
│  → htmlsnapshot query --sql @query.sql
├─ Live JS / complex DOM logic? → eval --json
├─ Several outputs from one page (text + links + images + a table)?
│  → scrape --formats "markdown,links,images"   # one capture for all of them
├─ A screenshot or a PDF as a *file* on this machine?
│  → scrape --formats "screenshot,pdf" --output ./out
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

### How to Process at Scale

```
Need to process multiple pages?
├─ Single list page (search results)? → htmlsnapshot query with LOAD_AND_SELECT
├─ List of known URLs (in a file)? → crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ Crawl from a start URL (follow links)? → crawl <url> --out-link-selector "..." --depth N
├─ Need parallel execution (high throughput)? → swarm create → swarm query --seed-file ...
├─ Repeated monitoring (check every hour)? → loop -i 3600 -- eval "..."
└─ Just a few URLs in a shell script?
   → browser4-cli open --headed "https://first-url"   # humans: open once, visibly
   → for url in ...; do browser4-cli goto "$url"; ... done
```

### How to Turn HTML into Spreadsheets — Zero LLM Tokens

[WebMiner](https://github.com/platonai/web-miner) runs ML clustering on downloaded HTML files to produce structured spreadsheets and interactive reports — **no LLM tokens, everything runs locally.** webminer is a first-class Browser4 CLI citizen: `browser4-cli webminer install` + `browser4-cli webminer all <html-dir>` runs the whole pipeline without PowerShell.

```
Have HTML files and want structured data — without LLM Tokens?
├─ < 20 pages? → browser4-cli crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ < 1,000 pages (small to medium)? → WebMiner Free (SMILE ML engine)
│  browser4-cli webminer install
│  browser4-cli webminer all ./pages/
│  → Interactive HTML report + Excel spreadsheets — local, zero cost
├─ > 1,000 pages (production scale)? → WebMiner Commercial (Apache Spark ML)
│  Same encode → cluster → views pipeline, distributed across machines
└─ Need to acquire pages first?
   ├─ Single pages: browser4-cli htmlsnapshot export
   ├─ Bulk download: browser4-cli crawl --seed-file urls.txt --depth 0
   └─ High throughput: browser4-cli swarm create → swarm query --seed-file ...
       Then feed the HTML directory to WebMiner
```

> **Pipeline:** `encode` (HTML → feature vectors → CSV) → `cluster` (KMeans, auto-detected K) → `views` (HTML report + Excel). Free tier uses the [SMILE](https://haifengl.github.io/) ML library for single-machine clustering (< 1,000 pages). Requires JDK 17+ (auto-detected). See [web-miner](https://github.com/platonai/web-miner) and `browser4-cli help webminer` for usage.

---

## 📦 Installation

Manual installation is optional since your AI agent is smart enough to install it after reading the SKILL.

Install browser4-cli globally using npm (requires Node.js):

```shell
npm install -g browser4-cli
browser4-cli install
```

Or bootstrap the native binary directly with a single command. The scripts
also install the Browser4 backend (runtime bundle) afterwards — running
`browser4-cli install` on a fresh machine, or `browser4-cli upgrade` when a
backend is already present (pass `--skip-backend` to skip this step):

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
```

## 💡 CLI Guide for Humans

`browser4-cli` is a human-usable browser automation shell, not just an agent backend. You can drive a real browser, inspect state, extract structured data, run X-SQL, orchestrate crawl/swarm jobs, manage server plugins and skills, and hand long-running work to built-in AI features.

If you want the embedded agent-facing instructions, see [skills/browser4-cli/SKILL.md](skills/browser4-cli/SKILL.md). This section is the human reference.

### Quick start

```bash
# Open a visible browser session (humans usually want to see the window;
# agents should prefer the default headless mode — see SKILL.md)
browser4-cli open --headed https://browser4.io

# Inspect the page and get element refs
browser4-cli snapshot --boxes

# Interact using a ref from the snapshot
browser4-cli click e15
browser4-cli fill e16 "Browser4" --submit

# Extract data from the live page
browser4-cli get text "h1"

# Capture the page into the store, then extract from that copy
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#main-content"
browser4-cli htmlsnapshot query --sql @query.sql

# Save output
browser4-cli screenshot --full-page --filename page.jpg
browser4-cli pdf --filename page.pdf
```

### Mental model

1. **Session-oriented**: commands work against the current browser session; use `-s <name>` for isolated named sessions.
2. **Two page views**: `snapshot` is for interactive work with element refs like `e15`; `htmlsnapshot` is for DOM/X-SQL extraction with CSS selectors.
3. **Interactive vs static extraction**: use `click`, `fill`, `type`, `press`, `wait` when the page must be manipulated first; use `htmlsnapshot query` when you need structured extraction from the DOM.
4. **Synchronous vs async jobs**: `agent`, `swarm`, `crawl`, and async chat-style commands return task IDs you poll later.

### Global options

These flags can appear before any command.

| Flag | Meaning |
|---|---|
| `-h`, `--help [command\|category]` | Show top-level help, category help, or detailed command help. `--help category:<name>` always lists a section (use it when the name is also a command, e.g. `category:install`); `--help hidden:<prefix>` lists a hidden family (`hidden:code`, `hidden:config`). The reference follows `COLUMNS` (clamped to 80–160) |
| `--help-json` | Emit the machine-readable command reference |
| `-v`, `--version` | Print the CLI version |
| `-s`, `--session <name>` | Use a named session instead of the default session. A **global** flag: place it **before** the command (`browser4-cli -s job-42 snapshot`) — after the command it is rejected as a positional argument |
| `--server <url>` | Override the Browser4 server URL |
| `--timeout <seconds>` | Override the HTTP timeout for the current command |
| `--proxy <url>` | Proxy used for runtime downloads/install operations |
| `--json` | Emit machine-readable JSON only |
| `--pretty` | Pretty-print JSON output |
| `-q`, `--quiet` | Suppress normal human-readable output |
| `--show-tip`, `-tip` | Show a relevant tip on stderr after commands |

### Key concepts before the command list

#### Element refs vs CSS selectors

- `snapshot` returns accessibility-tree refs such as `e5`, `e12`, `e42`
- most interaction commands accept either a snapshot ref or a CSS selector
- `htmlsnapshot` commands use CSS selectors, not accessibility refs

#### `snapshot` vs `htmlsnapshot`

| Tool | Best for | Input model | Output model |
|---|---|---|---|
| `snapshot` | clicking, typing, finding interactive elements | live accessibility tree | refs like `e15` |
| `htmlsnapshot` | DOM inspection, CSS extraction, X-SQL | a fresh snapshot of the active page (every command captures the tab first, then reads; `--expires <dur>` reads the stored snapshot instead) | CSS selectors and query results |

#### LLM configuration

AI-powered commands such as `extract`, `summarize`, `chat`, `agent run`, and X-SQL `llm_*` functions require an LLM provider key.

| Provider | Environment variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

> Configure **one** provider. When several provider keys are present, the first one in
> Browser4's provider order wins — the order lives in
> `browser4-core/browser4-resources/src/main/resources/config/llm/providers.json`, so a
> leftover `DEEPSEEK_API_KEY` silently wins over `OPENAI_*`, while `OPENAI_API_KEY` wins
> over `GROQ_API_KEY`.
> `browser4-cli doctor` prints the key that won (`Selected key`) and the model the backend
> will request (`Active model`). See [LLM configuration](docs/config.md#which-provider-is-used).

### Command cheat sheet

`browser4-cli --help <command>` prints the flags of any single command, and the
**[full CLI reference](docs/cli-reference.md)** is the authoritative list of every
command, option and platform note. This table is the day-to-day subset.

| Area | Commands |
|---|---|
| Sessions & server | `open`, `attach`, `close`, `list`, `status`, `doctor`, `stop`, `kill all`, `install`, `upgrade`, `uninstall` |
| Navigation | `goto`, `go back`, `go forward`, `reload` |
| Interaction | `snapshot`, `click`, `dblclick`, `hover`, `fill`, `type`, `press`, `select`, `check`, `uncheck`, `drag`, `upload`, `wait`, `frames`, `frame` |
| Keyboard & mouse | `keydown`, `keyup`, `mousemove`, `mousedown`, `mouseup`, `mousewheel`, `scroll` |
| Inspection & extraction | `get`, `eval`, `console`, `cdp`, `screenshot`, `pdf`, `htmlsnapshot`, `generate locator`, `resize`, `dialog accept`, `dialog dismiss` |
| Tabs & storage | `tab list`, `tab new`, `tab close`, `tab select`, `state save`, `state load`, `cookie`, `localstorage`, `sessionstorage`, `webdb` |
| AI (needs an LLM key) | `extract`, `summarize`, `chat`, `chat result`, `agent run`, `agent status`, `agent result`, `agent list` |
| Automation at scale | `batch`, `loop`, `swarm`, `crawl`, `webminer` |
| Network | `network requests`, `network request`, `network har`, `network route`, `network unroute` |
| Plugins, tools & skills | `plugin`, `tool list`, `tool call`, `skills`, `skill-*` |
| Experience memory | `experience save`, `experience query`, `experience list`, `experience deep learn` |

Interaction commands take a `snapshot` ref (`e15`) or a CSS selector, and most of them
support `--no-snapshot` to skip the automatic post-action snapshot.

```bash
browser4-cli open --headed https://example.com     # headless is the default
browser4-cli snapshot -i --boxes                   # refs for the next steps
browser4-cli fill e4 "john@example.com" --submit
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli agent run "compare the first 3 keyboards" --wait
```

Four rules that catch most people out:

- **`htmlsnapshot` reads the live tab.** Every read captures the active tab first, so
  rerunning a read sees the page as it is now; add `--expires 1d` to read the stored
  copy instead and leave the tab untouched.
- **`batch` runs on the client.** Each step is its own backend call that behaves like
  the standalone command; a failed step is reported and the batch continues unless
  `--bail`, and the exit code is `5` when any step failed.
- **`networkidle` is not "results rendered".** It only proves the network went quiet —
  poll the element (`wait "<result-selector>"`) for late-rendered content.
- **`--proxy` is a download proxy.** It covers install/upgrade only and never routes
  page traffic; page traffic is configured through [IP proxy](docs/ip-proxy.md).

### Timeout environment variables

| Variable | Default | Used for |
|---|---:|---|
| `BROWSER4_CLI_HTTP_TIMEOUT_SECS` | `30` | most commands |
| `BROWSER4_CLI_INPUT_TIMEOUT_SECS` | `90` | `type`, `fill`, and other slower input workflows |
| `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` | `120` | `goto`, `reload`, `go back`, `go forward` |

```bash
export BROWSER4_CLI_INPUT_TIMEOUT_SECS=180
export BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS=300
```

### State persistence

CLI state lives under `~/.browser4` unless overridden.

- default session: `~/.browser4/cli-state.json`
- named sessions: `~/.browser4/sessions/<name>.json`
- loops: `~/.browser4/loops/<name>.json`

The runtime bundle is stored separately in a platform-conventional application-data directory, so clearing session state does not force a re-download of Browser4 itself.

---

## 🚀 Build from Source

**Prerequisites:** Git, JDK 25+ (Eclipse Temurin), Chrome/Chromium, and PowerShell 7 (Linux/macOS only). For the full prerequisites table, platform-specific tools, and Chrome auto-detection paths, see [Build from Source](docs/build-from-source.md).

1. **Clone the repository**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **Configure your LLM API key**

   > Edit [application.properties](application.properties) and add your API key, or set environment variables. See [LLM Configuration](#llm-configuration) for supported providers and variable names.

3. **Build the project**
   ```shell
   ./mvnw -DskipTests
   ```

4. **Build and run the CLI (from source)**
   ```shell
   # Build the Rust CLI (requires Rust toolchain)
   cd cli/browser4-cli && cargo build --release

   # Or run directly without installing:
   cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help

   # Add --quiet to suppress Cargo build-status output:
   cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml -- <command>

   # Or install globally:
   cd cli/browser4-cli && cargo install --path .
   ```
   > On Windows, prefix the command with `chcp 65001 >nul &&` for proper UTF-8 output.
   > See [Build from Source](docs/build-from-source.md) for full platform-specific instructions.

   **Dev-mode wrappers (no install needed):** The repo root provides shell wrappers
   that auto-build from source. Use `./b4w.ps1 <command>` (PowerShell),
   `./b4w.sh <command>` (Git Bash / Linux / macOS), or `./b4w.bat <command>`
   (CMD) — all accept the same arguments as the installed `browser4-cli` binary.
   Dev mode only ever serves the checked-out code: a local runtime bundle built
   from a **different project version** makes the command refuse to start (naming
   the reason, the bundle's versions and build time, and the rebuild command)
   rather than silently testing an outdated backend; a checkout whose sources
   merely look newer than the bundle jars is rebuilt before the server starts —
   see
   [CLI install & upgrade](docs/cli-install-upgrade.md#dev-mode-the-backend-must-match-the-checkout).

---

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## Architecture

```
browser4-cli (Rust) ──MCP over HTTP──▶ browser4-rest (Kotlin/Spring) ──▶ PulsarWebDriver (Kotlin/CDP)
```

- **CLI** (`cli/browser4-cli`) — native Rust binary, talks to the backend via MCP tool calls
- **Backend** (`browser4-rest`) — Spring Boot server, dispatches MCP tools to browser drivers
- **Browser driver** (`browser4-core/browser4-browser`) — wraps Chrome DevTools Protocol
- **Replaceable browser behavior** (`browser4-core/browser4-behavior-api` + `browser4-behavior-default`) — stealth and interaction-humanization SPI; the current implementation is the default (community) edition, and a professional/custom edition replaces the module — see [browser-behavior-modules.md](docs/browser-behavior-modules.md)
- **Agent tools** (`browser4-agentic`) — maps MCP tool names to browser automation methods
- **Programming kernel** (`browser4-coding`) — dependency-light agent toolkit (sandboxed shell/filesystem, scaffolding, validation, self-development tools) — see [below](#-programming-agent-kernel-browser4-coding)

## 📦 Modules Overview

| Module | Description                                                          |
|---|----------------------------------------------------------------------|
| `cli/browser4-cli` | Rust CLI — fast, native binary for browser automation                |
| `skills/browser4-cli` | AI agent skill definitions (SKILL.md)                                |
| `browser4-core` | Core engine: sessions, scheduling, DOM, browser control              |
| `browser4-dependencies` | BOM and dependency version alignment                                 |
| `browser4-tools` | Operational tools and launch helpers                                 |
| `browser4-agentic` | AI agents, MCP integration, skill registration                       |
| `browser4-coding` | Programming-agent kernel — sandboxed shell/fs, artifact scaffolding & validation, self-development tools (50+ `coding.*` tools) |
| `browser4-agent-tools` | High-level agent tools: scraping, crawling, stateful page interaction |
| `browser4-rest` | Spring Boot REST layer & command endpoints                           |
| `browser4-apps/browser4-standalone` | Product packaging — unified launcher (`target/Browser4.jar`)         |
| `examples/browser4-examples` | Runnable examples and demos                                          |
| `browser4-tests` | E2E, integration, and scenario test suites                           |
| `cdp-protocol` | Chrome DevTools Protocol JSON definitions                            |
| `coworker/` | Builtin AI coworker                                                  |

---

## 🧩 Programming-Agent Kernel (browser4-coding)

`browser4-coding` is the dependency-light programming kernel that lets an AI agent create Browser4 artifacts **and** develop Browser4 itself. It is independent of `browser4-agentic` and `pulsar-common` (only SLF4J + Jackson + coroutines), so it can be reused by non-agent hosts. Heavy backends (LSP servers, kotlin-compiler-embeddable) are probed at runtime and never downloaded by default.

The `coding` domain exposes **50+ tools** in four groups:

| Group | Count | Highlights |
|---|---|---|
| Shell & filesystem | 32 | sandboxed `coding.shell` (command whitelist) + `runCode` snippet execution, snapshot-based edit primitives with `revert`, `diff` (Myers/Patience), repo-governance protection (`coding.protect`) |
| Artifact creation & validation | 5 | `scaffold` (plugin/skill/js/script), `scaffoldToDir`, `scaffoldFlow` (multi-file dev-flow), `scaffoldFromExample` (anti-staleness live templates, directory mode + stem-derived renames), `validate` (incl. `repo-consistency`) |
| Self-development | 13 | `mvnBuild` (structured diagnostics), `ktSymbols`/`ktReferences`/`ktInheritance`/`classInfo` (zero-dep Kotlin analysis), `impact` + `moduleGraph` (live pom graph), `devTask` + `resumeDevTask` (AGENTS.md flow + execution), `verify` (deterministic gate verdict), `trapCheck` (CDP pitfalls), `tokenStats`/`estimateTokens` (context budget) |
| LSP | 4 | on-demand `diagnostics`/`symbols`/`references`/`lspServers` for ts/js/py/rs (degrades gracefully when a server is missing) |

**Generic vs project-specific**: the kernel is layered by *mechanism vs data* — diff, sandbox, LSP client, Kotlin analysis, Maven passthrough and the pom-graph scanner are generic and portable; the scaffolds, validators, `ModuleMap`, `CdpTrapCheck` and the governance defaults encode Browser4 conventions and are the layer to rewrite when reusing the kernel elsewhere.

- Full tool reference & workflows: `skills/browser4-coding/SKILL.md`
- Developing Browser4 itself: `skills/browser4-dev/SKILL.md`
- Four-artifact comparison examples (real vs scaffold output): `docs-dev/copilot/examples/`
- Evaluation summary (P1–P7): `docs-dev/copilot/browser4-programming-support-eval.md`

---

## 🧪 Test Fixture Server (MockSite)

Browser4 includes a lightweight **MockSite** server that serves static HTML pages for testing and demos. Start it from the repository root:

**Windows (pwsh — PowerShell 7+):** `./bin/test.ps1 mock-site -Dmock.site.port=18080`
**Linux/macOS:** `./bin/test.sh mock-site -Dmock.site.port=18080`

> **First run on a cold Maven cache takes 10–15 minutes** (observed ~13 min) and prints nothing
> while it installs `browser4-dependencies`, then `browser4-rest` and its ~25 modules — the first
> `[PASS]` banner only appears after each phase finishes. Once the artifacts are cached, startup is
> fast. Don't kill the process and start a second concurrent Maven build.

Key demo pages are served at `http://localhost:18080/generated/`. For the full page listing, environment variables, Python fallback, and Maven-based launch, see [MockSite](docs/mocksite.md). For the test taxonomy and tagging system, see [Test Taxonomy](docs/TESTING.md).

---

## 🤝 Support & Community

Join our community for support, feedback, and collaboration!

- **GitHub Discussions**: Engage with developers and users.
- **Issue Tracker**: Report bugs or request features.
- **Social Media**: Follow us for updates and news.

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

### Contact Us

- 💬 WeChat: galaxyeye
- 🌐 Weibo: [galaxyeye](https://weibo.com/galaxyeye)
- 📧 Email: galaxyeye@live.cn, ivincent.zhang@gmail.com
- 🐦 Twitter: galaxyeye8

<div style="display: flex;">
  <img src="docs/images/wechat-author.png" width="300" height="365" alt="WeChat QR Code" />
</div>

---

## 📜 Documentation

Comprehensive documentation is available in the `docs/` directory and on our [GitHub Pages site](https://platonai.github.io/browser4/).

---

## 🔧 Proxy Configuration - Unblock Website Access

<details>

Set the environment variable `PROXY_ROTATION_URL` to the rotation URL provided by your proxy service provider:

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

Each time you access this rotation URL, it should return a response containing one or more fresh proxy IPs.
If you need this type of URL, please contact your proxy service provider.

For a single fixed proxy (`--proxy-server`), proxy authentication, the format the rotation
endpoint must return, and how to verify the egress IP, see **[docs/ip-proxy.md](docs/ip-proxy.md)**.
Note that `browser4-cli --proxy` is a *download* proxy — it does not route page traffic.

</details>

---

## License

Apache 2.0 License. See [LICENSE](LICENSE) for details.

## Links

* [omdsh-dev/dsh-data-agent](https://github.com/omdsh-dev/dsh-data-agent) - DSH Data Agent · Analyze data through conversation
* [ccch1mneyyy/dsh-TUI](https://github.com/ccch1mneyyy/dsh-TUI) - DSH TUI · Text-based User Interface

