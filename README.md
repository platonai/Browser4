# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

English | [简体中文](README.zh.md) | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**Table of Contents**
- [🤖 Browser4](#-browser4)
  - [🌟 Introduction](#-introduction)
    - [✨ Key Capabilities](#-key-capabilities)
  - [Quick Start](#quick-start)
  - [🧭 Tool Selection Guide](#-tool-selection-guide)
    - [How to Interact with a Page](#how-to-interact-with-a-page)
    - [How to Extract Data](#how-to-extract-data)
    - [How to Process at Scale](#how-to-process-at-scale)
    - [How to Turn HTML into Spreadsheets — Zero Tokens](#how-to-turn-html-into-spreadsheets--zero-tokens)
  - [📦 Installation](#-installation)
  - [💡 CLI Guide for Humans](#-cli-guide-for-humans)
    - [Quick start](#quick-start-1)
    - [Mental model](#mental-model)
    - [Global options](#global-options)
    - [Key concepts before the command list](#key-concepts-before-the-command-list)
    - [Complete command reference](#complete-command-reference)
    - [Timeout environment variables](#timeout-environment-variables)
    - [State persistence](#state-persistence)
  - [🚀 Build from Source](#-build-from-source)
  - [Architecture](#architecture)
  - [📦 Modules Overview](#-modules-overview)
  - [🧪 Test Fixture Server (MockSite)](#-test-fixture-server-mocksite)
  - [🤝 Support & Community](#-support--community)
  - [📜 Documentation](#-documentation)
  - [🔧 Proxy Configuration](#-proxy-configuration---unblock-website-access)
  - [License](#license)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4 — an AI-native browser engine for autonomous agents, intelligent extraction, and large-scale web automation.** 💖

### ✨ Key Capabilities

* 🤖 **Agent Browser** — Enable AI agents to browse, interact, and automate real-world websites.
* 🧠 **ML-Powered Extraction** — Learn page structures and extract structured data without LLM token costs.
* ⚡ **High-Performance Runtime** — Coroutine-safe architecture supporting 100k–200k complex page visits per machine per day.
* 🧬 **Hybrid Intelligence** — Combine LLM, ML, X-SQL, and selectors for robust extraction and experience reuse.
* 📦 **Enterprise-Scale Automation** — Swarm crawling, CDP-native control, batch jobs, stateful sessions, plugins, extensions, and more.

## Quick Start

Paste the following instruction to your favorite AI agent like claude, codex, workbuddy or openclaw and run it:

```
Read https://browser4.io/SKILL.md and install browser4-cli (if not installed) for browser automation to perform the following task:

1. go to amazon.com
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
```

## 🧭 Tool Selection Guide

Choosing the right tool for your task:

### How to Interact with a Page

Use `snapshot -i --boxes` to see clickable/typeable elements with refs like `e15`, then `click <ref>`, `fill <ref> "<text>"`, `type`/`press`, `select`, `hover`/`drag`/`scroll`, and `wait` to drive the page. All interaction commands accept CSS selectors too. Chain multiple steps efficiently with `batch`.

Typical interactive flow:

```bash
browser4-cli goto https://example.com/login
browser4-cli snapshot -i --boxes
browser4-cli fill e3 "user@example.com"
browser4-cli fill e4 "secret" --submit
browser4-cli wait --load networkidle
browser4-cli snapshot -i
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
├─ Natural language ("find the product price")? → extract (needs LLM key)
└─ High volume, many pages? → crawl or swarm with --sql
```

### How to Process at Scale

```
Need to process multiple pages?
├─ Single list page (search results)? → htmlsnapshot query with DOM_LOAD_AND_SELECT
├─ List of known URLs (in a file)? → crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ Crawl from a start URL (follow links)? → crawl <url> --out-link-selector "..." --depth N
├─ Need parallel execution (high throughput)? → swarm create → swarm query --seed-file ...
├─ Repeated monitoring (check every hour)? → loop -- eval "..." -i 3600
└─ Just a few URLs in a shell script?
   → for url in ...; do browser4-cli goto "$url"; ... done
```

### How to Turn HTML into Spreadsheets — Zero Tokens

[WebMiner](https://github.com/platonai/web-miner) runs ML clustering on downloaded HTML files to produce structured spreadsheets and interactive reports — **no LLM tokens, everything runs locally.**

```
Have HTML files and want structured data — without tokens?
├─ < 20 pages? → browser4-cli crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ < 1,000 pages (small to medium)? → WebMiner Free (SMILE ML engine)
│  java -jar scent-miner.jar all ./pages/
│  → Interactive HTML report + Excel spreadsheets — local, zero cost
├─ > 1,000 pages (production scale)? → WebMiner Commercial (Apache Spark ML)
│  Same encode → cluster → views pipeline, distributed across machines
└─ Need to acquire pages first?
   ├─ Single pages: browser4-cli htmlsnapshot export
   ├─ Bulk download: browser4-cli crawl --seed-file urls.txt --depth 0
   └─ High throughput: browser4-cli swarm create → swarm query --seed-file ...
       Then feed the HTML directory to WebMiner
```

> **Pipeline:** `encode` (HTML → feature vectors → CSV) → `cluster` (KMeans, auto-detected K) → `views` (HTML report + Excel). Free tier uses the [SMILE](https://haifengl.github.io/) ML library for single-machine clustering (< 1,000 pages). Requires JDK 17+. See [web-miner](https://github.com/platonai/web-miner) for install instructions.

---

## 📦 Installation

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

## 💡 CLI Guide for Humans

`browser4-cli` is a human-usable browser automation shell, not just an agent backend. You can drive a real browser, inspect state, extract structured data, run X-SQL, orchestrate crawl/swarm jobs, manage server plugins and skills, and hand long-running work to built-in AI features.

If you want the embedded agent-facing instructions, see [skills/browser4-cli/SKILL.md](skills/browser4-cli/SKILL.md). This section is the human reference.

### Quick start

```bash
# Open a browser session (headless by default; add --headed to see the window)
browser4-cli open https://browser4.io

# Or explicitly open a visible browser:
browser4-cli open --headed https://browser4.io

# Inspect the page and get element refs
browser4-cli snapshot --boxes

# Interact using a ref from the snapshot
browser4-cli click e15
browser4-cli fill e16 "Browser4" --submit

# Extract data from the live page
browser4-cli get text "h1"

# Capture a static DOM snapshot for repeated extraction
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#main-content"
browser4-cli htmlsnapshot query --sql @query.sql

# Save output
browser4-cli screenshot --full-page --filename page.png
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
| `-h`, `--help [command\|category]` | Show top-level help, category help, or detailed command help |
| `--help-json` | Emit the machine-readable command reference |
| `-v`, `--version` | Print the CLI version |
| `-s`, `--session <name>` | Use a named session instead of the default session |
| `--server <url>` | Override the Browser4 server URL |
| `--timeout <seconds>` | Override the HTTP timeout for the current command |
| `--proxy <url>` | Proxy used for runtime downloads/install operations |
| `--json` | Emit machine-readable JSON only |
| `--pretty` | Pretty-print JSON output |
| `-q`, `--quiet` | Suppress normal human-readable output |
| `-tip`, `--show-tip` | Show a relevant tip on stderr after commands |

### Key concepts before the command list

#### Element refs vs CSS selectors

- `snapshot` returns accessibility-tree refs such as `e5`, `e12`, `e42`
- most interaction commands accept either a snapshot ref or a CSS selector
- `htmlsnapshot` commands use CSS selectors, not accessibility refs

#### `snapshot` vs `htmlsnapshot`

| Tool | Best for | Input model | Output model |
|---|---|---|---|
| `snapshot` | clicking, typing, finding interactive elements | live accessibility tree | refs like `e15` |
| `htmlsnapshot` | DOM inspection, CSS extraction, X-SQL | stored HTML snapshot | CSS selectors and query results |

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

### Complete command reference

#### Session lifecycle and server administration

| Command | Description |
|---|---|
| `open [url]` | Open a browser session or reconnect to an existing one. **Headless by default.** Supports `--headed` (visible window), `--headless`, `--profile <path>`, `--profile-mode <DEFAULT\|SYSTEM_DEFAULT\|SEQUENTIAL\|TEMPORARY>`, `--interact-level <FASTEST\|FAST\|DEFAULT>`. |
| `attach` | Attach to an existing browser via CDP or the Browser4 extension. Supports `--cdp <url\|port\|channel>` and remote endpoint options. |
| `close` | Close the active browser session. |
| `list` | List browser sessions with their status and next-open behavior. Supports `--all`. |
| `session-default <name>` | Make a named session become the default unnamed session. |
| `close-all` | Close all sessions without stopping the backend. |
| `kill-all` | Force-stop the backend and Browser4-managed browser processes. |
| `stop` | Gracefully stop the Browser4 server. |
| `status` | Show server version, port, and health. |
| `doctor` | Run diagnostics: build info, LLM status, stale daemon cleanup, optional repair. Supports `--verbose` and `--fix`. |
| `doctor log [name]` | List, view, tail, or grep backend log files. Supports `--tail`, grep-style flags, and `doctor log <name> grep <pattern>`. |
| `doctor metrics [filter]` | List, filter, or grep backend metrics. Supports `doctor metrics grep <pattern>`. |
| `delete-data` | Delete session data. |
| `install` | Install the Browser4 runtime bundle. Supports `--tag <version>` and `--force`. |
| `upgrade` | Upgrade the CLI/runtime bundle. Supports `--tag <version>` and `--force`. |
| `uninstall` | Remove global installs and runtime data. Supports `-y`, `--yes`, and `--dry-run`. |

```bash
browser4-cli open --headed https://example.com
browser4-cli attach --cdp chrome
browser4-cli doctor --verbose
browser4-cli doctor log server.log --tail
browser4-cli doctor metrics grep request
```

#### Navigation

| Command | Description |
|---|---|
| `goto <url>` | Navigate to a URL; auto-opens/reconnects a session if needed. |
| `go-back` | Go back in browser history. |
| `go-forward` | Go forward in browser history. |
| `reload` | Reload the current page. |

#### Core interaction

All interaction commands accept a snapshot ref such as `e15` or a CSS selector unless noted otherwise. Most of them also support `--no-snapshot` to skip the automatic post-action accessibility snapshot.

| Command | Description |
|---|---|
| `click <ref> [button]` | Click an element. Supports `--modifiers`, `--follow`, `--auto-dismiss-dialogs`. |
| `dblclick <ref> [button]` | Double-click an element. Supports `--modifiers`, `--follow`, `--auto-dismiss-dialogs`. |
| `hover <ref>` | Hover over an element. |
| `fill <ref> <text>` | Clear and fill text into an editable field. Supports `--submit`, `--verify`. |
| `type <text> [ref]` | Type text into the focused element or a target element. Supports `--submit`, `--verify`, `--focus`, `--interactable-timeout`. |
| `press <key> [ref]` | Press a key on the focused element or a target element. Supports `--verify`, `--follow`. |
| `select <ref> <value>` | Select a dropdown value. Supports `--verify`. |
| `check <ref>` | Check a checkbox or radio button. |
| `uncheck <ref>` | Uncheck a checkbox or radio button. |
| `drag <startRef> <endRef>` | Drag and drop from one element to another. |
| `wait [target]` | Wait for a selector/ref, duration, text, URL pattern, page-load state, or JavaScript expression. Supports `--timeout`, `--text`, `--url`, `--load`, `--fn`. |

`wait --load` accepts `domcontentloaded`, `load`, and `networkidle`.

```bash
browser4-cli click e8 --follow
browser4-cli fill e4 "john@example.com" --submit
browser4-cli type "Browser4" e7 --verify
browser4-cli wait --text "Success"
browser4-cli wait --load networkidle
```

#### Keyboard and mouse

| Command | Description |
|---|---|
| `keydown <key>` | Press and hold a key. |
| `keyup <key>` | Release a held key. |
| `mousemove <x> <y>` | Move the mouse to screen/page coordinates. |
| `mousedown [button]` | Press a mouse button. |
| `mouseup [button]` | Release a mouse button. |
| `mousewheel <dx> <dy>` | Scroll using a wheel delta. |
| `scroll <direction> <pixels>` | Scroll the page `up`, `down`, `left`, or `right`. |

#### Page inspection and live extraction

| Command | Description |
|---|---|
| `snapshot` | Capture an accessibility-tree snapshot. Supports `--boxes`, `-i/--interactive`, `-u/--urls`, `-c/--compact`, `--no-compact`, `-d/--depth`, `-l/--limit`, `-s/--selector`, `--raw`, `--stdout`, `-vp/--viewport`, `--filename`. |
| `snapshot grep <pattern>` | Search saved/current snapshot YAML with grep-style flags such as `-i`, `-v`, `-c`, `-l`, `-F`, `-w`, `-A`, `-B`, `-C`, `--selector`, `--page`, `--page-size`, `--all`. |
| `snapshot list` | List saved snapshot files with timestamps and sizes. |
| `snapshot clean` | Remove old snapshot files. Supports `--dry-run`. |
| `get <mode> <selector> [name]` | Extract `text`, `html`, `box`, `styles`, `property`, or `attr` from a live page element. |
| `eval [expression] [ref]` | Evaluate JavaScript on the page or an element. Supports `--file`, `--stdin`, `--base64`, `--await`, `--wait-selector`, `--json`. |
| `console [min-level]` | List browser console messages. Supports `--clear`. |
| `cdp <method>` | Send an arbitrary Chrome DevTools Protocol command. Supports `--json <params>`. |
| `generate-locator <ref>` | Generate the best CSS selector for a snapshot ref or existing selector. |
| `resize <width> <height>` | Resize the browser window. |
| `dialog-accept [prompt]` | Accept an alert/confirm/prompt dialog, optionally filling the prompt. |
| `dialog-dismiss` | Dismiss an alert/confirm/prompt dialog. |

`get` modes:

| Mode | Meaning | Example |
|---|---|---|
| `text` | visible inner text | `browser4-cli get text ".price"` |
| `html` | inner HTML | `browser4-cli get html "#main"` |
| `box` | bounding box | `browser4-cli get box "#hero"` |
| `styles` | computed styles | `browser4-cli get styles e9` |
| `property` | DOM property value | `browser4-cli get property "input" value` |
| `attr` | HTML attribute value | `browser4-cli get attr "a" href` |

```bash
browser4-cli snapshot -i --boxes
browser4-cli snapshot grep -C 2 "button"
browser4-cli eval "document.title"
browser4-cli eval --file script.js --await
browser4-cli console warn
browser4-cli cdp Runtime.evaluate --json '{"expression":"document.title"}'
```

#### HTML snapshot and X-SQL extraction

`htmlsnapshot` captures a stored raw DOM snapshot and is the center of Browser4's structured extraction workflow.

| Command | Description |
|---|---|
| `htmlsnapshot` | Short form of `htmlsnapshot capture`. |
| `htmlsnapshot capture` | Capture and store a static HTML snapshot with metadata about the page and interactive elements. |
| `htmlsnapshot get <field> [selector] [name]` | Extract the first matching `text`, `html`, or `attr` from the stored snapshot. |
| `htmlsnapshot get all <field> [selector] [name]` | Extract all matching values from the stored snapshot. Supports `--offset` and `--limit`. |
| `htmlsnapshot query [url]` | Run X-SQL. Supports `--sql <query\|@file>`, `--sql-stdin`, `--sql-base64`, result pagination, and extraction-focused output flags. |
| `htmlsnapshot export` | Export stored HTML to a file. Supports positional file path or `--file <path>` plus `--clean`. |
| `htmlsnapshot summary` | Generate a compressed Web Page Summary Index (WPSI). |
| `htmlsnapshot grep <pattern>` | Search stored HTML with grep-style flags. |
| `htmlsnapshot inspect [selector]` | Discover recurring DOM patterns and selector candidates. Supports `--max`, `--depth`, `--stdin`, `--selector-base64`. |

Important rules:

- use `snapshot` when you need refs and interaction
- use `htmlsnapshot` when you need repeated DOM extraction
- `htmlsnapshot query --sql @query.sql` is the recommended way to avoid shell quoting issues
- for correlated list extraction, prefer `htmlsnapshot query` over repeated `get all`

```bash
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get all text ".result-title" --offset 10 --limit 5
browser4-cli htmlsnapshot inspect ".s-result-item" --depth 6 --max 20
browser4-cli htmlsnapshot export --file page.html --clean
browser4-cli htmlsnapshot query --sql @query.sql
```

For deep X-SQL usage, see [skills/browser4-cli/references/htmlsnapshot.md](skills/browser4-cli/references/htmlsnapshot.md) and [skills/browser4-cli/references/x-sql-dom-load-select.md](skills/browser4-cli/references/x-sql-dom-load-select.md).

#### Screenshots and PDF

| Command | Description |
|---|---|
| `screenshot [ref]` | Take a page or element screenshot. Supports `--filename`, `--full-page`, `--viewport`. |
| `pdf` | Save the current page as PDF. Supports `--filename`. |

#### Tabs

| Command | Description |
|---|---|
| `tab-list` | List open tabs with indexes, titles, and URLs; use `--json` for full GUIDs. |
| `tab-new [url]` | Open a new tab, optionally navigating to a URL. |
| `tab-close [index]` | Close a tab by index; supports `--guid <guid>`. |
| `tab-select <index>` | Switch to a tab by index; supports `--guid <guid>`. |

#### Browser storage and local page data

| Command | Description |
|---|---|
| `state-save [filename]` | Save cookies and localStorage to a JSON file. |
| `state-load <filename>` | Restore cookies and localStorage from a JSON file. |
| `cookie-list` | List cookies. Supports `--domain`, `--path`. |
| `cookie-get <name>` | Get a cookie by name. |
| `cookie-set <name> <value>` | Set a cookie. Supports `--domain`, `--path`, `--expires`, `--httpOnly`, `--secure`, `--sameSite`. |
| `cookie-delete <name>` | Delete a cookie by name. Supports `--domain`, `--path`. |
| `cookie-clear` | Clear all cookies. |
| `localstorage-list` | List localStorage entries. |
| `localstorage-get <key>` | Read a localStorage key. |
| `localstorage-set <key> <value>` | Set a localStorage key. |
| `localstorage-delete <key>` | Delete a localStorage key. |
| `localstorage-clear` | Clear localStorage. |
| `sessionstorage-list` | List sessionStorage entries. |
| `sessionstorage-get <key>` | Read a sessionStorage key. |
| `sessionstorage-set <key> <value>` | Set a sessionStorage key. |
| `sessionstorage-delete <key>` | Delete a sessionStorage key. |
| `sessionstorage-clear` | Clear sessionStorage. |
| `webdb export <dir>` | Export pages from the Browser4 web database to a local directory. |
| `webdb normalize <url>` | Normalize a URL into the web database key format. |

#### AI extraction, chat, and autonomous agent tasks

These commands require an LLM key.

| Command | Description |
|---|---|
| `extract <instruction>` | Extract structured data from the current page. Supports `--schema <json\|@file>`, `--filename`, `--raw`, `--stdout`. |
| `summarize [instruction]` | Summarize the current page. Supports `--selector`, `--filename`, `--raw`, `--stdout`. |
| `chat <message>` | Send a plain AI chat request without auto-appended browser context. |
| `chat-result <id>` | Retrieve the result of an async chat task. |
| `agent run <task>` | Submit an autonomous browser task and immediately receive a task ID. |
| `agent status <id>` | Check a running task. |
| `agent result <id>` | Fetch a completed result. |
| `agent list` | List tracked agent tasks and their status. |

```bash
browser4-cli extract "product name, price, rating"
browser4-cli extract "contacts" --schema @schema.json
browser4-cli summarize --selector "#reviews"
browser4-cli agent run "Go to amazon.com, compare the first 3 keyboards, write a summary"
browser4-cli agent status agent-task-1
```

#### Batch and loop automation

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation. Supports `--bail` and `--json` for stdin-driven command arrays. |
| `loop [task]` | Run a task repeatedly. Supports `--name`, `-i/--interval`, `-n/--count`, `-t/--timeout`, `--shell`, `--list`, `--pause`, `--resume`, `--pause-all`, `--resume-all`, `--stop`, `--stop-all`, `--status`, `--history`, `--keep-state`. |

Batch-compatible commands:

```text
goto  go-back  go-forward  reload  press  type  keydown  keyup
click  dblclick  hover  fill  select  check  uncheck  drag
mousemove  mousedown  mouseup  mousewheel  scroll  wait
get  eval  snapshot  screenshot  pdf  dialog-accept  dialog-dismiss
resize  tab-list  tab-new  tab-close  tab-select
```

```bash
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli loop "load https://example.com and extract the title" -i 300 -n 10
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60
browser4-cli loop --list
```

#### Swarm and crawl for scale

The `co` prefix is accepted as an alias for `swarm`.

| Command | Description |
|---|---|
| `swarm create` | Create a parallel scraping session. Supports `--profile-mode`, `--max-open-tabs`, `--max-browser-contexts`, `--display-mode`. |
| `swarm submit [url]` | Submit URLs or X-SQL payloads as jobs. Supports `--seed-file`, `--sql`, `--deadline`, `--expires`, `--refresh`, `--parse`. |
| `swarm query <url>` | Run an X-SQL extraction job against one or more loaded pages. Supports `--sql`, `--seed-file`, `--deadline`, `--expires`, `--refresh`. |
| `swarm status <id>` | Check a swarm task status. |
| `swarm result <id>` | Fetch a completed swarm result. |
| `swarm list` | List tracked swarm tasks. |
| `swarm close` | Close the swarm session and release browser resources. |
| `crawl [url]` | Crawl from a URL or seed file. Supports `--seed-file`, `--sql`, `--sql-stdin`, `--sql-base64`, `--format`, `--output`, `-d/--depth`, `-ol/--out-link-selector`, `-olp/--out-link-pattern`, `-tl/--top-links`, `-a/--args`, `--refresh`, `--parse`, `--expires`, `-p/--priority`, `--page-load-timeout`, `--ignore-url-query`, `--no-norm`, `--readonly`, `-bg/--background`. |
| `crawl status <id>` | Check crawl task status. |
| `crawl result <id>` | Fetch crawl results. |
| `crawl cancel <id>` | Cancel a running crawl. |
| `crawl clear` | Remove terminal-state crawl tasks; supports force-style cleanup options. |
| `crawl list` | List tracked crawl tasks. |

```bash
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm query --seed-file urls.txt --sql @query.sql --refresh
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli crawl list
```

#### Bundled skill files vs installed runtime skills

Browser4 has two different "skill" surfaces:

1. **`skills ...`** manages bundled, embedded skill documents that ship with the CLI.
2. **`skill-*`** manages installed runtime skills exposed by the backend.

##### Bundled CLI skills

| Command | Description |
|---|---|
| `skills` | List bundled skill names. |
| `skills list` | Same as `skills`. |
| `skills get <name>` | Print a skill's `SKILL.md`. Supports `--full` and `--all`. |
| `skills path [name]` | Print the bundled skill directory path. |
| `skills unpack [dest]` | Unpack bundled skill files to a directory. |

##### Installed runtime skills

| Command | Description |
|---|---|
| `skill-list` | List installed backend skills. |
| `skill-info <id>` | Show detailed skill metadata. |
| `skill-install <path>` | Install a skill from a directory containing `SKILL.md`. Supports `--overwrite`. |
| `skill-uninstall <id>` | Remove a skill by ID. |
| `skill-reload <id>` | Reload a skill from its source directory. |

#### Progressive experience memory

These commands operate on Browser4's learned experience store.

| Command | Description |
|---|---|
| `experience save <url> <trace>` | Save a task execution trace. Supports `--outcome`, `--intent`, `--task-type`. |
| `experience query <url>` | Query known selectors, blockers, and hints for a URL/domain. Supports `--intent`. |
| `experience list` | List stored experience entries. Supports `--filter`, `--intent-filter`, `--page`, `--page-size`. |
| `experience deep-learn <url> <intent>` | Run deeper analysis on stored traces. Supports `--force`. |

#### Plugins

Plugins are server-side JARs that extend Browser4.

| Command | Description |
|---|---|
| `plugin list` | List installed plugins. |
| `plugin info <name>` | Show plugin details. |
| `plugin install <file>` | Install a plugin from a local JAR file. Supports `--replace`. |
| `plugin remove <name>` | Remove a plugin. Supports `-y`, `--yes`. |

#### Advanced and currently hidden commands

These commands exist in the CLI but are intentionally kept out of the default public help.

| Command | Description |
|---|---|
| `upload <ref> <file>` | Upload one or multiple files to a file input. |
| `act <description>` | Experimental natural-language action translator that turns plain text into a browser command and runs it. |

### Timeout environment variables

| Variable | Default | Used for |
|---|---:|---|
| `BROWSER4_CLI_HTTP_TIMEOUT_SECS` | `30` | most commands |
| `BROWSER4_CLI_INPUT_TIMEOUT_SECS` | `90` | `type`, `fill`, and other slower input workflows |
| `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` | `120` | `goto`, `reload`, `go-back`, `go-forward` |

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
- **Agent tools** (`browser4-agentic`) — maps MCP tool names to browser automation methods

## 📦 Modules Overview

| Module | Description                                                          |
|---|----------------------------------------------------------------------|
| `cli/browser4-cli` | Rust CLI — fast, native binary for browser automation                |
| `skills/browser4-cli` | AI agent skill definitions (SKILL.md)                                |
| `browser4-core` | Core engine: sessions, scheduling, DOM, browser control              |
| `browser4-dependencies` | BOM and dependency version alignment                                 |
| `browser4-tools` | Operational tools and launch helpers                                 |
| `browser4-agentic` | AI agents, MCP integration, skill registration                       |
| `browser4-agent-tools` | High-level agent tools: scraping, crawling, stateful page interaction |
| `browser4-rest` | Spring Boot REST layer & command endpoints                           |
| `browser4-apps/browser4-standalone` | Product packaging — unified launcher (`target/Browser4.jar`)         |
| `examples/browser4-examples` | Runnable examples and demos                                          |
| `browser4-tests` | E2E, integration, and scenario test suites                           |
| `cdp-protocol` | Chrome DevTools Protocol JSON definitions                            |
| `coworker/` | Builtin AI coworker                                                  |

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
