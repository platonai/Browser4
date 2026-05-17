---
name: browser4-cli
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
allowed-tools: Bash(browser4-cli:*)
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents.

- Chrome/Chromium via CDP with accessibility-tree snapshots, playwright CLI compatible commands
- Build-in agent loop for autonomous agents with tool use and reasoning capabilities
- Data extraction and summarization tools for processing web content

Install: `npm i -g browser4-cli`

## Quick start

```bash
# open new browser
browser4-cli open
# navigate to a page with the current active session
browser4-cli goto https://browser4.io/
# take a snapshot
browser4-cli snapshot
# interact with the page using refs from the snapshot
browser4-cli click e15
browser4-cli type "page.click"
browser4-cli press Enter
# take a screenshot
browser4-cli screenshot
# close the browser
browser4-cli close
```

`browser4-cli goto` only reuses the current active session. If no active session is available, or the
saved session is no longer active, run `browser4-cli open` first to create or refresh the session.

`browser4-cli open` reuses the saved session for the current slot only when the backend still reports it
as active. If the saved session is stale or missing, `open` refreshes it by creating a new session.

## Commands

The sections below cover the standard browser workflow commands that are surfaced in the global `browser4-cli help` overview.

### Core

```bash
browser4-cli open
# open and navigate right away in one step
browser4-cli open https://browser4.io/
# navigate to a URL using the current active session
browser4-cli goto https://playwright.dev
browser4-cli type "search query"
browser4-cli click e3
browser4-cli dblclick e7
browser4-cli fill e5 "user@example.com"
browser4-cli drag e2 e8
browser4-cli hover e4
browser4-cli select e9 "option-value"
browser4-cli check e12
browser4-cli uncheck e12
browser4-cli snapshot
browser4-cli snapshot --filename=after-click.yaml
browser4-cli eval "document.title"
browser4-cli resize 1920 1080
browser4-cli close
```

### Navigation

```bash
browser4-cli goto <url>         # Navigate to a URL using the current active session
browser4-cli go-back
browser4-cli go-forward
browser4-cli reload
```

Before calling `goto`, make sure you have already created or refreshed the session with `browser4-cli open`.

### Keyboard

```bash
browser4-cli press Enter
browser4-cli press ArrowDown
browser4-cli keydown Shift
browser4-cli keyup Shift
```

### Mouse

```bash
browser4-cli mousemove 150 300
browser4-cli mousedown
browser4-cli mousedown right
browser4-cli mouseup
browser4-cli mouseup right
browser4-cli mousewheel 0 100
```

### Save as

```bash
browser4-cli screenshot
browser4-cli screenshot e5
browser4-cli screenshot --filename=page.png
```

### Tabs

```bash
browser4-cli tab-list
browser4-cli tab-new
browser4-cli tab-new https://example.com/page
browser4-cli tab-close
browser4-cli tab-close 2
browser4-cli tab-select 0
```

Use `browser4-cli tab-list` to obtain the current zero-based tab index before calling `tab-select` or `tab-close` with a specific target.

## Open parameters
```bash
# Start with profile mode
browser4-cli open
browser4-cli open https://browser4.io

# Close the browser
browser4-cli close
```

## Snapshots

After commands that modify browser state, browser4-cli usually provides a snapshot of the current browser state.

```bash
> browser4-cli goto https://example.com
### Page
- Page URL: https://example.com/
- Page Title: Example Domain
### Snapshot
[Snapshot](.browser4-cli/snapshot/page-2026-02-14T19-22-42-679Z.yml)
```

You can also take a snapshot on demand using `browser4-cli snapshot` command.

If `--filename` is not provided, a new snapshot file is created with a timestamp. Default to automatic file naming, use `--filename=` when artifact is a part of the workflow result.

## Browser Sessions

```bash
# create new browser session named "mysession"
browser4-cli -s=mysession open example.com
browser4-cli -s=mysession click e6
browser4-cli -s=mysession close  # stop a named browser
browser4-cli list
# Close all sessions, but keep Browser4.jar / the Browser4 backend running
browser4-cli close-all
# Explicitly stop Browser4.jar / the Browser4 backend and kill Browser4 browser processes
browser4-cli kill-all
```

`browser4-cli list` shows both the saved session state (`Active`, `Stale`, or `Unknown`) and what the
next `browser4-cli open` will do for each slot (`Reuse` or `Refresh`).

## Advanced commands

Some advanced commands are intentionally omitted from the global `browser4-cli help` summary.
Query them explicitly when needed:

```bash
browser4-cli help batch
browser4-cli help console
browser4-cli help extract
browser4-cli help summarize
browser4-cli help agent-run
browser4-cli help co-create
```

## Installation

### Global Installation (recommended)

Installs the native Rust binary:

```bash
npm install -g browser4-cli
```

After installation, use `browser4-cli`.

## Example: Form submission

```bash
browser4-cli open https://example.com/form
browser4-cli snapshot

browser4-cli fill e1 "user@example.com"
browser4-cli fill e2 "password123"
browser4-cli click e3
browser4-cli snapshot
browser4-cli close
```

## Example: Multi-tab workflow

```bash
browser4-cli open https://example.com
browser4-cli tab-new https://example.com/other
browser4-cli tab-list
browser4-cli tab-select 0
browser4-cli snapshot
browser4-cli close
```
