---
title: "Page Scrape Command Reference"
description: "Reference for the `page scrape` and `page formats` commands. One request, one page capture, many output formats returned together in a single Firecrawl-compatible document."
tier: procedure
---

# Page Scrape Command Reference

Produce several outputs from a page in **one** request. `page scrape --formats
markdown,links,images` loads the page once and returns markdown, the links and the
images together, all describing the same page state.

This is the difference from issuing one command per output: `htmlsnapshot get` and
`htmlsnapshot readability` each capture the page before they answer, so asking for
three outputs that way is three page loads and three possibly-different page states.
Here the capture is shared.

## Quick Start

```bash
browser4-cli page scrape --formats "markdown,links"        # quote the comma list
browser4-cli page scrape                                   # defaults to markdown
browser4-cli page scrape --formats '["markdown","links"]'  # JSON array form
browser4-cli page formats                                  # what this build can deliver
```

On an existing session, `page scrape` needs no arguments beyond what you ask for:
it reads the page the session is already on.

## When to Use

Use `page scrape` when you want:

- **several outputs from one page** — markdown plus the links, the images, an
  attribute table, an X-SQL extraction;
- **a consistent view** — because every field comes from one capture, `markdown`
  and `links` cannot disagree about what the page said;
- **a cheaper multi-output read** — one page load, not one per format.

Prefer the single-purpose commands when you want exactly one output and want it in a
particular shape:

| You want | Use |
|---|---|
| Just the article text | `htmlsnapshot readability` |
| Just an X-SQL result set | `htmlsnapshot query` |
| Element values by CSS selector, live DOM | `get <mode> <selector>` |
| Several outputs from one page, once | **`page scrape`** |

## How It Works

```
--formats ──▶ one capture ──▶ every format reads that capture ──▶ one document
```

The response is a single JSON document. Fields for formats that produced nothing are
absent; `metadata.formatsRequested` and `metadata.formatsDelivered` tell you what was
asked for and what came back:

```json
{
  "url": "https://example.com/",
  "markdown": "…",
  "links": ["https://iana.org/help/example-domains"],
  "metadata": {
    "captureId": "https://example.com/",
    "captureTime": "2026-10-06T03:00:23.582Z",
    "formatsRequested": ["markdown", "links"],
    "formatsDelivered": ["markdown", "links"]
  }
}
```

`captureId` and `captureTime` are the proof that one capture served everything:
there is exactly one of each per response.

**A format that produced nothing is not a failure.** A page with no `<img>` yields
an empty `images`, which the wire omits — but `formatsDelivered` still lists
`images`, because it *was* requested and *did* run. That is how you tell "requested,
produced nothing" from "not requested at all". A format this build cannot deliver is
listed in neither, and is named in `warning`.

## Patterns

**Several outputs, one load:**

```bash
browser4-cli page scrape --formats "markdown,links,images,readability"
```

**A format with its own options** (an object instead of a name):

```bash
browser4-cli page scrape --formats '[{"type":"markdown"},{"type":"attributes","selectors":[{"selector":".price","attribute":"data-amount"}]}]'
```

**A deterministic extraction** — `deterministicJson` takes X-SQL, so the result is
reproducible rather than model-generated:

```bash
browser4-cli page scrape --formats '["markdown",{"type":"deterministicJson","sql":"select dom_first_text(dom, \'h1\') as title"}]'
```

**Scrape a URL other than the current page:**

```bash
browser4-cli page scrape --url "https://example.com/post" --formats "markdown"
```

**Discover before you ask** — `page formats` lists every accepted id, whether this
deployment can deliver it, and why not when it cannot:

```bash
browser4-cli page formats
browser4-cli page formats | grep -o '"id":"[a-zA-Z]*"'    # just the ids
```

**Checking what actually came back** — read `formatsDelivered`, not the presence of a
field:

```bash
browser4-cli page scrape --formats "markdown,links" | grep -o '"formatsDelivered":\[[^]]*\]'
```

## Flags

| Flag | Meaning |
|---|---|
| `--formats <list>` | The outputs to produce, in request order. Accepts a comma-separated string (`"markdown,links"`), a JSON array (`'["markdown","links"]'`) or a single name. An entry may be an object carrying that format's options. Omitted or empty means `["markdown"]` |
| `--url <url>` | Scrape this URL instead of the session's current page |
| `--onlyMainContent <bool>` | Derive markdown from the readable article rather than the whole cleaned page. Default `true` |
| `--sessionId <id>` | Added automatically by the CLI; you do not pass it |

`page formats` takes no flags.

**Available formats in a stock install** — `markdown`, `html`, `rawHtml`, `links`,
`images`, `attributes`, `deterministicJson`, `readability`. Everything else the
command accepts (`screenshot`, `pdf`, `json`, `summary`, `question`, `audio`,
`video`, `changeTracking`, `rawBase64`) is either a later phase or needs a plugin;
`page formats` is the authority for the build you are running, not this list.

`branding`, `product`, `menu` and `highlights` are **contributor** formats: they
exist only once a plugin provides them, and `page formats` reports
`"no plugin contributor installed"` until one is.

## Errors & Recovery

| Symptom | Meaning | Fix |
|---|---|---|
| `Unknown format 'markdwon'. Known formats: …` | A format id this build does not accept. Nothing was captured | Check the spelling against the printed list, or run `page formats` |
| `Missing required parameter: sessionId` | Called over raw HTTP without a session | Use the CLI (it injects one), or pass `sessionId` in the JSON body. There is no "auto-open a session" mode yet |
| Only the first format came back, and the exit code was 0 | Your shell split the comma list: in PowerShell an *unquoted* `a,b,c` is three arguments, so `--formats` received only `a` | Quote it: `--formats "markdown,links,images"`. `formatsRequested` in the response shows what the server actually received |
| The field you asked for is absent | Either the format produced nothing, or it is unavailable here | Read `formatsDelivered`: listed means it ran; `warning` names the ones that did not and why |
| `warning` names a format you did want | That format is unavailable or failed; the rest still succeeded | Install the plugin named in the message, or drop the format |
| `Unsupported page method: scrape links` | A `tool call page_scrape` invocation with an unquoted list: `links` became part of the method name | Use `page scrape`, or `tool call page_scrape --json '{"formats":["markdown","links"]}'` |

A request never fails because one *optional* format was unavailable — that is a
`warning` and the other formats are still returned. A request does fail when a format
it depends on cannot be produced at all, and the error keeps the original message.
