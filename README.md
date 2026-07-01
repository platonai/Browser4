# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

English | [简体中文](README.zh.md) | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**Table of Contents**
- [🤖 Browser4](#-browser4)
  - [🌟 Introduction](#-introduction)
    - [✨ Key Capabilities](#-key-capabilities)
  - [📦 Installation](#-installation)
  - [💡 Usage Examples](#-usage-examples)
    - [Quick Start](#quick-start)
    - [CLI & SKILLS](#cli--skills)
      - [LLM Configuration](#llm-configuration)
  - [🚀 Build from Source](#-build-from-source)
  - [🧬 Auto Extraction](#-auto-extraction)
  - [📦 Modules Overview](#-modules-overview)
  - [🧪 Test Fixture Server (MockSite)](#-test-fixture-server-mocksite)
  - [🤝 Support & Community](#-support--community)
  - [📜 Documentation](#-documentation)
  - [🔧 Proxy Configuration](#-proxy-configuration---unblock-website-access)
  - [License](#license)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4: a lightning-fast, coroutine-safe browser engine for your AI** 💖

### ✨ Key Capabilities

* 👽 **Browser Agents** — Fully autonomous browser agents that reason, plan, and execute end-to-end tasks.
* 🤖 **Browser Automation** — High-performance automation for workflows, navigation, and data extraction.
* ⚙️ **Machine Learning Agent** — Learns field structures across complex pages without consuming tokens.
* ⚡ **Extreme Performance** — Fully coroutine-safe; supports 100k ~ 200k complex page visits per machine per day.
* 🧬 **Data Extraction** — Hybrid of LLM, ML, X-SQL and selectors for clean data across chaotic pages.

## Quick Start

Just ask any LLM agent to use browser4-cli for browser interactions, and it will be able to perform complex tasks like this:

```shell
$prompt = @"
Read https://browser4.io/SKILL.md and install browser4-cli for browser automation to perform the following task:

1. go to amazon.com
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
"@

# copilot -p "$prompt"
claude -p "$prompt"
```

## 📦 Manually Installation (Optional)

Manually installation is optional since your AI agent is smart enough to install it after reading the SKILL.

Install browser4-cli globally using npm (requires Node.js):

```shell
npm install -g browser4-cli
browser4-cli install
```

Or bootstrap the native binary directly with a single command:

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
browser4-cli install
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
browser4-cli install
```

## 💡 Usage Examples

### CLI & SKILLS

Browser4 CLI is a powerful command-line interface for direct browser control and automation, designed for both human
users and AI agents. It provides a simple syntax to perform complex browser interactions without writing code.

Browser4 CLI is compatible with Playwright and supports a wide range of commands for navigation, interaction, and data extraction.
It can be used in scripts, terminal sessions, or integrated into AI agents through SKILLS.

Commands are designed to be intuitive and composable, allowing you to chain multiple actions together for complex workflows.

Browser4 CLI is designed for use by AI agents through SKILLS + CLI — see [SKILL.md](skill/SKILL.md).

#### Global Flags

These flags can appear before any command:

```
-s=<name>, --session=<name>    Named session label
--server=<url>                 Override Browser4 server URL
--json                         Emit machine-parseable JSON to stdout
-q, --quiet                    Suppress normal output, show only errors
--proxy=<url>                  Manual HTTP proxy for downloads
--help, -h                     Print help
--version, -v                  Print version
```

#### Session Lifecycle

```
open [url]        Open a browser session, optionally navigating to a URL
                  --headed, --headless, --profile=<path>, --profile-mode=<mode>
attach            Attach to an existing browser via CDP (--cdp=<channel|url|port>)
close             Close the current browser session
close-all         Close all browser sessions without stopping the backend
kill-all          Forcefully stop the Browser4 backend and kill all browser processes
list [--all]      List browser sessions with status and next-open behavior
stop              Gracefully stop the Browser4 server
status            Show Browser4 server status (version, port, health)
delete-data       Delete session data
```

#### Navigation

```
goto <url>        Navigate to a URL, auto-opening/reconnecting a session if needed
go-back           Go back to the previous page
go-forward        Go forward to the next page
reload            Reload the current page
```

#### Core Interaction

```
click <ref> [button]       Click an element. --modifiers
dblclick <ref> [button]    Double-click an element. --modifiers
hover <ref>                Hover over an element
fill <ref> <text>          Clear and fill text into an editable element. --submit, --verify
type <text> [ref]          Type text into the focused element or a target ref. --submit, --verify, --focus
press <key> [ref]          Press a key on the focused element or a target ref. --verify
select <ref> <value>       Select an option in a dropdown. --verify
check <ref>                Check a checkbox or radio button
uncheck <ref>              Uncheck a checkbox or radio button
drag <startRef> <endRef>   Drag and drop between two elements
upload <ref> <file>        Upload files to a file input
wait [target]              Wait for a condition: element, time (--text), URL (--url), page load (--load), or JS (--fn)
```

#### Keyboard & Mouse

```
keydown <key>                 Press and hold a key
keyup <key>                   Release a key
mousemove <x> <y>             Move the mouse to a position
mousedown [button]            Press a mouse button
mouseup [button]              Release a mouse button
mousewheel <dx> <dy>          Scroll the mouse wheel
scroll <direction> <pixels>   Scroll the page (up/down/left/right)
```

#### Page Inspection

```
snapshot                          Capture an accessibility-tree snapshot
                                  --boxes, --interactive (-i), --urls (-u), --compact (-c),
                                  --depth (-d), --selector (-s), --raw, --viewport (-vp), --filename
get <mode> <selector> [name]      Extract data using CSS selectors
                                  Modes: text, html, box, styles, property, attr
eval [expression] [ref]            Evaluate JavaScript on the page or an element. --file=<path>
console [min-level]                List browser console messages. --clear
generate-locator <ref>             Generate a unique CSS selector from a snapshot ref or existing selector
```

#### DOM Snapshot (static DOM extraction)

The `domsnapshot` family captures raw HTML DOM for querying with CSS selectors and X-SQL — no interactive browser session required.

```
                  snapshot              domsnapshot
─────────────────────────────────────────────────────────
Data source       Accessibility tree    Raw HTML DOM
Element refs      e5, e15               CSS selectors only
Interactive       click, type, fill     Not supported
X-SQL support     No                    Yes (query)
```

```
domsnapshot                                Capture a static DOM snapshot and store it in page storage
domsnapshot get <field> [selector] [name]  Extract text, html, or attr from the stored DOM snapshot
domsnapshot query [url]                    Run X-SQL against the stored DOM snapshot (--sql=<query|@file>)
domsnapshot export                         Export snapshot HTML to a local file (--file=<path>)
domsnapshot summary                        Generate a compressed Web Page Summary Index (WPSI)
domsnapshot grep <pattern>                 Search snapshot HTML with regex
                                           -i, -v, -c, -l, -F, -w, -A, -B, -C, --selector
```

For the full reference (including X-SQL `llm_*` functions that also require an LLM key), see the [DOM Snapshot reference](cli/skill/references/domsnapshot.md).

#### Export

```
screenshot [ref]    Screenshot the page or an element. --filename, --full-page
pdf                 Save the page as PDF. --filename
```

#### Tabs

```
tab-list            List all open tabs
tab-new [url]       Create a new tab, optionally navigating to a URL
tab-close [index]   Close a tab by zero-based index (omit for current tab)
tab-select <index>  Select a tab by zero-based index
```

#### Dialogs

```
dialog-accept [prompt]  Accept a browser dialog, optionally providing prompt text
dialog-dismiss          Dismiss a browser dialog
```

#### Window

```
resize <width> <height>   Resize the browser window
```

#### Storage: Cookies

```
cookie-list               List cookies. --domain, --path
cookie-get <name>         Get a cookie by name
cookie-set <name> <val>   Set a cookie. --domain, --path, --expires, --httpOnly, --secure, --sameSite
cookie-delete <name>      Delete a cookie by name. --domain, --path
cookie-clear              Clear all cookies
```

#### Storage: localStorage & sessionStorage

```
localstorage-list             List all localStorage entries
localstorage-get <key>        Get a localStorage value by key
localstorage-set <key> <val>  Set a localStorage value
localstorage-delete <key>     Delete a localStorage entry
localstorage-clear            Clear all localStorage
sessionstorage-list           List all sessionStorage entries
sessionstorage-get <key>      Get a sessionStorage value by key
sessionstorage-set <key> <val>Set a sessionStorage value
sessionstorage-delete <key>   Delete a sessionStorage entry
sessionstorage-clear          Clear all sessionStorage
```

#### Storage: State

```
state-save [filename]    Save cookies + localStorage to a JSON file
state-load <filename>    Load cookies + localStorage from a JSON file
```

#### LLM Configuration

AI-powered commands (`agent`, `extract`, `summarize`) and X-SQL `llm_*` functions require an LLM API key. Configure one provider via environment variables:

```
DeepSeek                   DEEPSEEK_API_KEY
OpenRouter                 OPENROUTER_API_KEY, OPENROUTER_MODEL_NAME, OPENROUTER_BASE_URL
Volcengine (ByteDance)     VOLCENGINE_API_KEY, VOLCENGINE_MODEL_NAME, VOLCENGINE_BASE_URL
OpenAI-compatible           OPENAI_API_KEY, OPENAI_MODEL_NAME, OPENAI_BASE_URL
Aliyun Qwen (DashScope)    OPENAI_API_KEY, OPENAI_MODEL_NAME, OPENAI_BASE_URL
```

These environment variables map to the corresponding properties in [application.properties](application.properties). Example:

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

If no valid LLM key is configured, AI commands fail fast with a clear error at startup.

#### AI / Agent

> **Requires LLM API key** — see [LLM Configuration](#llm-configuration) above.

Submit natural-language tasks and let Browser4's backend AI agent plan and execute autonomously:

```
agent run <task>          Submit an autonomous task (async, returns task ID immediately)
agent status <id>         Check the status of a running agent task
agent result <id>         Get the final result of a completed agent task
extract <instruction>     Extract structured data from the page using AI. --schema=<json>, --filename, --raw
summarize [instruction]   Summarize page content using AI. --selector, --filename, --raw
```

- `agent run` is asynchronous — the backend agent reasons, explores, and executes until the task is complete.
- Agent commands are task-ID based and do not require an active CLI browser session slot.
- Agent subcommands are not supported inside `batch` mode.

#### Swarm (parallel scraping)

Orchestrate parallel scraping across multiple browser contexts. The `co` prefix is accepted as an alias for `swarm`.

```
swarm create          Create a swarm scrape session
                      --profile-mode, --max-open-tabs, --max-browser-contexts, --display-mode
swarm submit [url]    Submit URLs or X-SQL payloads as scrape jobs
                      --seed-file, --sql, --deadline, --expires, --refresh, --parse, --store-content
swarm query <url>     Submit an X-SQL query to extract data from a loaded webpage
                      --sql, --seed-file, --deadline, --expires, --refresh
swarm status <id>     Check the status of a scrape job
swarm result <id>     Get the result of a completed scrape job
```

Seed files are plain text, one URL per line; `#` comments and blank lines are ignored. Use `@url` in X-SQL templates — it is replaced with the target URL server-side.

#### Crawl

```
crawl [url]     Crawl a website from a URL or seed file, with optional X-SQL extraction
                --seed-file (bulk URL list, one per line, # comments ignored)
                --sql (X-SQL extraction, inline or @file.sql), --sql-stdin, --sql-base64
                --format json|csv|table (default: table), --output (-o) file
                --depth (-d, default 1; 0 = no link discovery), --out-link-selector (-ol),
                --out-link-pattern (-olp), --top-links (-tl), --args (-a),
                --refresh, --parse, --expires, --store-content, --priority (-p),
                --page-load-timeout, --ignore-url-query, --no-norm, --readonly
```

#### Batch & Loop

```
batch <command...>  Execute multiple commands in one process
                    --bail (stop on first failure), --json (read JSON commands from stdin)
loop [task]         Execute a task repeatedly on an interval
                    --name, --interval (-i), --count (-n), --timeout (-t),
                    --shell, --list, --stop, --status
```

#### Install & Upgrade

```
install      Install the self-contained Browser4 runtime bundle. --tag=<version>, --force
uninstall    Remove globally installed browser4-cli and runtime data. --yes (-y), --dry-run
upgrade      Upgrade to the latest version or a specified release tag. --tag=<version>, --force
```

#### Batch-Compatible Commands

The following commands can be used inside `batch` and `batch --json`:

```
goto  go-back  go-forward  reload  press  type  keydown  keyup
click  dblclick  hover  fill  select  check  uncheck  drag  upload
mousemove  mousedown  mouseup  mousewheel  scroll  wait
get  eval  snapshot  screenshot  pdf  dialog-accept  dialog-dismiss
resize  tab-list  tab-new  tab-close  tab-select
```

#### CLI Timeout Configuration

Some commands may take longer than the default HTTP timeout. Use these environment variables to adjust timeouts:

```
BROWSER4_CLI_HTTP_TIMEOUT_SECS          30    Most commands (click, snapshot, screenshot, etc.)
BROWSER4_CLI_INPUT_TIMEOUT_SECS         90    Text input commands (type, fill)
BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS   120    Navigation commands (goto, reload, go-back, go-forward)
```

Text input commands use a longer default timeout because typing into form fields — especially on complex pages — can be slower than simple interactions. If a text input command times out, the operation **may have partially executed**. After a timeout, verify the field content with `snapshot` or `get` before retrying.

```shell
# Increase input timeout for heavy pages
export BROWSER4_CLI_INPUT_TIMEOUT_SECS=180

# Increase navigation timeout for slow sites
export BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS=300
```

#### Quick Examples

```shell
# Open a browser session
browser4-cli open --headed https://browser4.io

# Navigate to a page — auto-opens a session if none is active
browser4-cli goto https://browser4.io

# Inspect the page — note the eN labels on interactive nodes
browser4-cli snapshot --boxes

# Interact using refs from the snapshot
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter

# Extract data with CSS selectors
browser4-cli get text ".product-title"
browser4-cli get attr ".product-image" data-src

# DOM snapshot with X-SQL
browser4-cli domsnapshot
browser4-cli domsnapshot get text "#main-content"
browser4-cli domsnapshot query --sql @query.sql
browser4-cli domsnapshot grep -i "error"

# AI-powered extraction and summarization (requires LLM key — see LLM Configuration above)
browser4-cli extract "product name, price, and rating as JSON"
browser4-cli summarize "key points in 3 bullets"

# Autonomous agent task
browser4-cli agent run "Search amazon for mechanical keyboards, compare the top 3, write a summary"

# Parallel scraping with swarm
browser4-cli swarm create --max-open-tabs=12 --display-mode=HEADLESS
browser4-cli swarm submit --seed-file=./urls.txt --refresh --store-content
browser4-cli swarm result scrape-task-1

# Batch multiple commands
browser4-cli batch "goto https://browser4.io" "snapshot" "screenshot"

# Take a screenshot
browser4-cli screenshot --full-page

# Manage cookies and storage
browser4-cli cookie-list
browser4-cli state-save session.json

# Close the session when done
browser4-cli close
```

---

## 🚀 Build from Source

**Prerequisites:** Git, JDK 17+ (21+ recommended), Chrome/Chromium, and PowerShell 7 (Linux/macOS only). For the full prerequisites table, platform-specific tools, and Chrome auto-detection paths, see [Build from Source](docs/build-from-source.md).

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

---

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## 🧬 Auto Extraction

Automatic, large-scale, high-precision field discovery and extraction powered by self-/unsupervised machine learning — no LLM API calls, no tokens, deterministic and fast.

**What it does:**
- Learns every extractable field on item/detail pages (often dozens to hundreds) with high precision.
- Open source when Browser4 has 10K stars on GitHub.

**Why not just LLMs?**
- LLM extraction adds latency, cost, and token limits.
- ML-based auto extraction is local, reproducible, and scalable to 100k+ ~ 200k pages/day.
- You can still combine both: use Auto Extraction for structured baseline + LLM for semantic enrichment.

**Quick Commands (PulsarRPAPro):**
```bash
# NOTE: MongoDB required
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v3.0.0/PulsarRPAPro.jar
```

**Integration Status:**
- Available today via the companion project [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro).
- Native Browser4 API exposure is planned; follow releases for updates.

**Key Advantages:**
- High precision: >95% fields discovered; majority with >99% accuracy (indicative on tested domains).
- Resilient to selector churn & HTML noise.
- Zero external dependency (no API key) → cost-efficient at scale.
- Explainable: generated selectors & SQL are transparent and auditable.

👽 Extract data with machine learning agents:

![Auto Extraction Result Snapshot](docs/assets/images/amazon.png)

(Coming soon: richer in-repo examples and direct API hooks.)

---

## 📦 Modules Overview

```
cli                     CLI in Rust that supports SKILLS
browser4-core           Core engine: sessions, scheduling, DOM, browser control
browser4-agentic        Agent implementation, MCP, and skill registration
browser4-rest           Spring Boot REST layer & command endpoints
browser4-standalone     Agent & crawler orchestration with product packaging
examples                Runnable examples and demos
browser4-tests          E2E & heavy integration & scenario tests
```

---

## 🧪 Test Fixture Server (MockSite)

Browser4 includes a lightweight **MockSite** server that serves static HTML pages for testing and demos. Start it from the repository root:

**Windows:** `./bin/test.ps1 mock-site -Dmock.site.port=18080`
**Linux/macOS:** `./bin/test.sh mock-site -Dmock.site.port=18080`

Key demo pages are served at `http://localhost:18080/generated/`. For the full page listing, environment variables, Python fallback, and Maven-based launch, see [MockSite](docs/mocksite.md). For the test taxonomy and tagging system, see [Test Taxonomy](docs/TESTING.md).

---

## 🤝 Support & Community

Join our community for support, feedback, and collaboration!

- **GitHub Discussions**: Engage with developers and users.
- **Issue Tracker**: Report bugs or request features.
- **Social Media**: Follow us for updates and news.

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

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

</details>

---

## License

Apache 2.0 License. See [LICENSE](LICENSE) for details.
