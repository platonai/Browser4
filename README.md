# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

English | [简体中文](README.zh.md) | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**Table of Contents**
- [🤖 Browser4](#-browser4)
    - [🌟 Introduction](#-introduction)
        - [✨ Key Capabilities](#-key-capabilities)
    - [💡 Usage Examples](#-usage-examples)
        - [Quick Start](#quick-start)
        - [CLI \& SKILLS](#cli--skills)
    - [Advanced commands](#advanced-commands)
        - [Agent and Swarm CLI](#agent-and-swarm-cli)
    - [🚀 Native API Quick Start](#-native-api-quick-start)
        - [Auto Extraction](#auto-extraction)
    - [📦 Modules Overview](#-modules-overview)
    - [🤝 Support \& Community](#-support--community)
    - [📜 Documentation](#-documentation)
    - [🔧 Proxy Configuration - Unblock Website Access](#-proxy-configuration---unblock-website-access)
    - [License](#license)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4: a lightning-fast, coroutine-safe browser engine for your AI** 💖

### ✨ Key Capabilities

* 👽 **Browser Agents** — Fully autonomous browser agents that reason, plan, and execute end-to-end tasks.
* 🤖 **Browser Automation** — High-performance automation for workflows, navigation, and data extraction.
* ⚙️ **Machine Learning Agent** - Learns field structures across complex pages without consuming tokens.
* ⚡  **Extreme Performance** — Fully coroutine-safe; supports 100k ~ 200k complex page visits per machine per day.
* 🧬 **Data Extraction** — Hybrid of LLM, ML, and selectors for clean data across chaotic pages.

## 💡 Usage Examples

### Quick Start

Just ask any LLM agent to use browser4-cli for browser interactions, and it will be able to perform complex tasks like this:

```shell
$prompt = @"
Install https://raw.githubusercontent.com/platonai/Browser4/refs/heads/main/cli/skill/SKILL.md and use browser4-cli and perform the following task:

1. go to amazon.com
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
"@

copilot --allow-all -p "$prompt"
# claude --dangerously-skip-permissions "$prompt"
```

### CLI & SKILLS

Browser4 CLI is a powerful command-line interface for direct browser control and automation, designed for both human
users and AI agents. It provides a simple syntax to perform complex browser interactions without writing code.

Browser4 CLI is compatible with Playwright and supports a wide range of commands for navigation, interaction, and data extraction.
It can be used in scripts, terminal sessions, or integrated into AI agents through SKILLS.

Installs browser4-cli globally using npm:

```shell
npm install -g browser4-cli
```

```shell
# Open browser4 without navigation
browser4-cli open

# Open in headed or headless mode
browser4-cli open --headed https://browser4.io
browser4-cli open --headless https://browser4.io

# Navigate to a page — auto-opens a session if none is active
browser4-cli goto https://playwright.dev

# Inspect the page — note the eN labels on interactive nodes
browser4-cli snapshot

# Interact using refs from the snapshot
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# Take a screenshot and save it to disk
browser4-cli screenshot

# Use a custom server URL
browser4-cli open --server http://localhost:9090

# Execute multiple commands in one process
browser4-cli batch "goto https://playwright.dev" "snapshot"

# Stop on the first batch failure
browser4-cli batch --bail "goto https://playwright.dev" "click e1" "screenshot"

# Advanced: pipe batch commands as JSON via stdin
echo '[
  ["goto", "https://example.com/form-filling"],
  ["click", "#reset-btn"],
  ["fill", "#first-name", "Bob"],
  ["fill", "#last-name", "Smith"],
  ["fill", "#email", "bob@example.com"],
  ["select", "#country", "uk"],
  ["check", "#agree-terms"],
  ["click", "#submit-btn"]
]' | browser4-cli batch --json

# Close the session when done
browser4-cli close
```

## Advanced commands

Some advanced commands are intentionally omitted from the global `browser4-cli help` summary.
Query them explicitly when needed:

```bash
browser4-cli help batch
browser4-cli help extract
browser4-cli help summarize
browser4-cli help agent run
browser4-cli help swarm create
```

Browser4 CLI is designed for use by AI agents through SKILLS + CLI.

[SKILL.md](cli/skill/SKILL.md)

### Agent and Swarm CLI

Browser4 CLI provides two high-level interfaces for complex, multi-step browser tasks beyond the standard single-action commands:

**Agent CLI** (`agent <subcommand>`) — Submit a natural-language task and let Browser4's backend AI agent plan and execute it autonomously. The agent reasons about the page, decides which actions to take, and completes the task asynchronously. Use this when you have a goal but don't know the exact page structure, or need multi-step exploration without scripting every action.

**Swarm CLI** (`swarm <subcommand>`) — Orchestrate parallel scraping and structured data extraction across multiple browser contexts. Built for high-throughput jobs: refreshing curated URL lists, supervised fan-out browsing, and repeatable selector-based scraping. Supports X-SQL for structured queries against loaded webpages.

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Agent CLI | Natural-language task → autonomous execution | You have a goal but don't know the page structure; multi-step exploration |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

#### Agent CLI examples

Submit a natural-language task and let the backend agent reason, plan, and execute autonomously:

```shell
# Submit an autonomous task — returns a task ID immediately
browser4-cli agent run "Open example.com, find the sign-up form, fill it with test data, and take a screenshot"

# Poll progress with the returned task ID
browser4-cli agent status agent-task-1

# Read the final result once the task completes
browser4-cli agent result agent-task-1
```

What happens under the hood:
1. `agent run` sends the task to the Browser4 backend, which spawns an AI agent with tool access (navigate, click, type, snapshot, screenshot, extract, summarize, etc.).
2. The agent iteratively explores the page, takes snapshots, decides on actions, and executes them until the task is complete.
3. `agent status` returns the backend status payload (typically JSON with `id`, `status`, `statusCode`, `processState`, `agentState`, `agentHistory`, `commandResult`).
4. `agent result` returns the final task output — plain text or structured JSON depending on the task.

Key notes:
- `agent run` is asynchronous: it returns immediately after the backend accepts the task.
- `agent run` performs a quick post-submit probe so missing LLM/API key errors fail fast.
- Agent commands are task-ID based and do not require an active CLI browser session slot.
- Agent subcommands are not supported inside `batch` mode.

#### Swarm CLI examples

Create a swarm session, submit URLs for scraping, and collect results at scale:

```shell
# 1) Create a swarm scrape session with parallel browser contexts
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS

# 2) Submit URLs as scrape jobs (direct URL + seed file)
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./urls.txt \
  --refresh --parse --store-content

# 3) Poll and fetch the result
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

Run X-SQL queries to extract structured data from loaded webpages:

```shell
# Inline X-SQL query
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# Read query from a file
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql

# With seed file and load options
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh --parse
```

Key notes:
- Seed files are plain text, one URL per line; `#` comments and blank lines are ignored.
- Both `swarm submit` and `swarm query` accept `--seed-file`, `--deadline`, `--expires`, `--refresh`, `--parse`, `--store-content`.
- All swarm commands return a task ID; track progress with `swarm status` / `swarm result`.
- Use `@url` in X-SQL templates — it is replaced with the target URL server-side.

---

## 🚀 Build from source

**Prerequisites**: Java 17+

1. **Clone the repository**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **Configure your LLM API key**

   > Edit [application.properties](application.properties) and add your API key.

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

### Auto Extraction

Automatic, large-scale, high-precision field discovery and extraction powered by self-/unsupervised machine learning — no LLM API calls, no tokens, deterministic and fast.

**What it does:**
- Learns every extractable field on item/detail pages (often dozens to hundreds) with high precision.
- Open source when browser4 has 10K stars on GitHub.

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

| Module             | Description                                             |
|--------------------|---------------------------------------------------------|
| `cli`              | CLI in Rust that supports SKILLS                        |
| `browser4-core`    | Core engine: sessions, scheduling, DOM, browser control |
| `browser4-agentic` | Agent implementation, MCP, and skill registration       |
| `browser4-rest`    | Spring Boot REST layer & command endpoints              |
| `browser4-standalone`  | Agent & crawler orchestration with product packaging    |
| `examples`         | Runnable examples and demos                             |
| `browser4-tests`   | E2E & heavy integration & scenario tests                |

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
