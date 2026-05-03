---
name: browser4-cli
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
allowed-tools: Bash(browser4-cli:*)
---

# Browser Automation with browser4-cli

## Quick start

```bash
# open new browser
browser4-cli open
# navigate to a page
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

## Commands

### Core

```bash
browser4-cli open
# open and navigate right away
browser4-cli open https://example.com/
browser4-cli goto https://browser4.io/
browser4-cli type "search query"
browser4-cli click e3
browser4-cli dblclick e7
browser4-cli fill e5 "user@example.com"
browser4-cli drag e2 e8
browser4-cli hover e4
browser4-cli select e9 "option-value"
browser4-cli upload ./document.pdf
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
browser4-cli go-back
browser4-cli go-forward
browser4-cli reload
```

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

## Snapshots

After each command, browser4-cli provides a snapshot of the current browser state.

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

## Batch Mode

```bash
# Execute multiple commands in one process
browser4-cli batch "open https://example.com" "snapshot"

# Stop on the first batch failure
browser4-cli batch --bail "open https://example.com" "click e1" "screenshot"

# Pipe batch commands as JSON via stdin
echo '[
  ["open", "https://example.com"],
  ["snapshot"],
  ["click", "e1"],
  ["screenshot", "--filename=result.png"]
]' | browser4-cli batch --json
```

## Browser Sessions

```bash
browser4-cli list
# Close all browsers
browser4-cli close-all
# Forcefully kill all browser processes
browser4-cli kill-all
```

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
