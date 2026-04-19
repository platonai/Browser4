# browser4-cli Collective Commands

## Overview

The **Collective** commands (`co`) in `browser4-cli` enable multi-browser
parallel execution for faster and more efficient task handling. A collective
session creates multiple browser contexts and tabs to handle URL submission,
data scraping, and task execution in parallel.

---

## Quick Start

```bash
# 1. Create a collective session with 8 tabs and 2 contexts in GUI mode
browser4-cli co create --profile-mode=temporary --max-open-tabs=8 --max-browser-contexts=2 --display-mode=GUI

# 2. Submit a URL with a deadline
browser4-cli co submit https://www.amazon.com/dp/B08PP5MSVB -deadline 2026-02-24T23:59:59Z

# 3. Scrape data from a page
browser4-cli co scrape https://www.amazon.com/dp/B08PP5MSVB --selector=".product-title" --attribute="textContent" --output=title.txt

# 4. Close the session
browser4-cli close
```

---

## Commands

### `co create` — Create a Collective Session

Initialize a session with multiple browser contexts and tabs to handle parallel
tasks.

```
browser4-cli co create [options]
```

**Options:**

| Option                     | Description                                                          |
|----------------------------|----------------------------------------------------------------------|
| `--profile-mode`           | Browser profile mode: `temporary`, `default`, `system_default`, `prototype` |
| `--max-open-tabs`          | Maximum open tabs per browser context (default: 8)                   |
| `--max-browser-contexts`   | Number of isolated browser environments (default: 2)                 |
| `--display-mode`           | Display mode: `GUI`, `HEADLESS`, `SUPERVISED`                        |

**Examples:**

```bash
# Create a session with default settings
browser4-cli co create

# Create a headless session with 16 tabs across 4 contexts
browser4-cli co create --profile-mode=temporary --max-open-tabs=16 --max-browser-contexts=4 --display-mode=HEADLESS

# Create a GUI session for debugging
browser4-cli co create --display-mode=GUI
```

**How it works:**

The `co create` command calls the `open_session` MCP tool with a `capabilities`
map built from the CLI options. The capabilities map to:

- `profileMode` → `BrowserProfileMode` (e.g., `temporary`, `default`)
- `maxOpenTabs` → Controls concurrency per browser context
- `maxBrowserContexts` → Controls the number of isolated browser environments
- `displayMode` → `DisplayMode` (e.g., `GUI`, `HEADLESS`)

---

### `co submit` — Submit Tasks

Submit one or more URLs or tasks to the active collective session for async
processing.

```
browser4-cli co submit [url] [options]
```

**Arguments:**

| Argument | Required | Description                        |
|----------|----------|------------------------------------|
| `url`    | No*      | URL or task to submit              |

*Either `url` or `--seed-file` is required.

**Options:**

| Option            | Description                                                    |
|-------------------|----------------------------------------------------------------|
| `--seed-file`     | File containing URLs to submit, one per line                   |
| `--deadline`      | Deadline for task completion (ISO 8601, e.g. `2026-02-24T23:59:59Z`) |
| `--expires`       | Cache expiration duration (e.g. `1d`, `1h`)                    |
| `--refresh`       | Force a fresh fetch, ignoring cache                            |
| `--parse`         | Parse page immediately after fetching                          |
| `--store-content` | Persist page content to storage                                |

**Examples:**

```bash
# Submit a single URL with a deadline
browser4-cli co submit https://www.amazon.com/dp/B08PP5MSVB --deadline=2026-02-24T23:59:59Z

# Submit with cache control
browser4-cli co submit https://www.amazon.com/dp/B08PP5MSVB --expires=1d --refresh --parse

# Submit multiple URLs from a seed file
browser4-cli co submit --seed-file=seeds.txt

# Submit with content persistence
browser4-cli co submit https://example.com --store-content --parse
```

**Seed file format:**

```text
# Lines starting with # are comments
https://www.amazon.com/dp/B08PP5MSVB
https://www.amazon.com/dp/B09V3KXJPB
https://www.amazon.com/dp/B0BSHF7WHW
```

**How it works:**

Each URL is submitted asynchronously via `POST /api/commands/plain?async=true`.
The command returns immediately with a task ID for each URL. Load options
(`-deadline`, `-expires`, `-refresh`, `-parse`, `-storeContent`) are appended to
each URL command string and map directly to `LoadOptions` on the server.

---

### `co scrape` — Scrape Data

Extract specific data from pages using CSS selectors.

```
browser4-cli co scrape <url> [options]
```

**Arguments:**

| Argument | Required | Description          |
|----------|----------|----------------------|
| `url`    | Yes      | URL to scrape        |

**Options:**

| Option        | Description                                                    |
|---------------|----------------------------------------------------------------|
| `--selector`  | CSS selector to extract elements                               |
| `--attribute` | Element attribute to extract (e.g. `textContent`, `href`)      |
| `--output`    | Output file path for scraped data                              |
| `--deadline`  | Deadline for task completion (ISO 8601)                         |
| `--expires`   | Cache expiration duration (e.g. `1d`, `1h`)                    |
| `--refresh`   | Force a fresh fetch, ignoring cache                            |

**Examples:**

```bash
# Scrape product titles
browser4-cli co scrape https://www.amazon.com/dp/B08PP5MSVB --selector=".product-title" --attribute="textContent" --output=title.txt

# Scrape links from a page
browser4-cli co scrape https://example.com --selector="a" --attribute="href"

# Scrape with a fresh fetch
browser4-cli co scrape https://example.com --selector=".price" --refresh
```

---

### `co status` — Check Task Status

Check the status of a running collective task.

```
browser4-cli co status <id>
```

**Arguments:**

| Argument | Required | Description                             |
|----------|----------|-----------------------------------------|
| `id`     | Yes      | Task ID returned by `co submit` or `co scrape` |

**Examples:**

```bash
browser4-cli co status abc-123-def
```

---

### `co result` — Get Task Result

Get the result of a completed collective task.

```
browser4-cli co result <id>
```

**Arguments:**

| Argument | Required | Description                             |
|----------|----------|-----------------------------------------|
| `id`     | Yes      | Task ID returned by `co submit` or `co scrape` |

**Examples:**

```bash
browser4-cli co result abc-123-def
```

---

## Workflow Examples

### Batch Product Scraping

```bash
# 1. Create a high-concurrency session
browser4-cli co create --max-open-tabs=16 --max-browser-contexts=4 --display-mode=HEADLESS

# 2. Submit product URLs from a seed file
browser4-cli co submit --seed-file=product-urls.txt --parse --store-content

# 3. Check status of individual tasks
browser4-cli co status <task-id>

# 4. Get results
browser4-cli co result <task-id>

# 5. Close the session
browser4-cli close
```

### Single-Page Data Extraction

```bash
# 1. Create a session
browser4-cli co create --profile-mode=temporary --display-mode=GUI

# 2. Scrape data from a page
browser4-cli co scrape https://www.amazon.com/dp/B08PP5MSVB \
  --selector=".product-title" \
  --attribute="textContent" \
  --output=title.txt

# 3. Close the session
browser4-cli close
```

---

## Technical Notes

### Parameter Mapping

CLI parameters map directly to the underlying Agentic and Skeleton APIs.

**`co create`** parameters map to `AgenticContexts.createSession(...)`:

| CLI Option                 | API Property         | Type                 |
|----------------------------|----------------------|----------------------|
| `--profile-mode`           | `BrowserProfileMode` | `temporary`, `default`, etc. |
| `--max-open-tabs`          | `maxOpenTabs`        | Integer              |
| `--max-browser-contexts`   | `maxBrowserContexts` | Integer              |
| `--display-mode`           | `DisplayMode`        | `GUI`, `HEADLESS`, `SUPERVISED` |

**`co submit`** and **`co scrape`** options map to `LoadOptions`:

| CLI Option       | LoadOptions Property | Description                    |
|------------------|----------------------|--------------------------------|
| `--deadline`     | `deadline`           | Absolute time limit (ISO 8601) |
| `--expires`      | `expires`            | Cache expiration duration      |
| `--refresh`      | `refresh`            | Force fresh fetch              |
| `--parse`        | `parse`              | Parse after fetching           |
| `--store-content`| `storeContent`       | Persist content to storage     |

---

## REST API Mapping

| CLI Command      | REST Endpoint                                         | Method |
|------------------|-------------------------------------------------------|--------|
| `co create`      | `/mcp/call-tool` (tool: `open_session`)               | POST   |
| `co submit`      | `/api/commands/plain?async=true`                      | POST   |
| `co scrape`      | `/api/commands/plain?async=true`                      | POST   |
| `co status`      | `/api/commands/{id}/status`                           | GET    |
| `co result`      | `/api/commands/{id}/result`                           | GET    |

---

## Comparison: Agent vs Collective

| Feature              | Agent (`agent-run`)            | Collective (`co`)                  |
|----------------------|--------------------------------|------------------------------------|
| Session setup        | Implicit (auto-created)        | Explicit (`co create` with options)|
| Browser contexts     | Single context                 | Multiple parallel contexts         |
| Execution model      | Sequential observe→act cycles  | Parallel URL processing            |
| Best for             | Complex single-page AI tasks   | High-volume page scraping          |
| Resource usage       | Low (1 browser)                | Configurable (N contexts × M tabs) |

---

## See Also

- [browser4-cli-agent.md](browser4-cli-agent.md) — Single-agent AI commands
- [rest-api-examples.md](rest-api-examples.md) — REST API usage examples
- [LoadOptions.kt](../browser4-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt) — Load options reference
- [AgenticContexts.kt](../pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/AgenticContexts.kt) — Session creation API
