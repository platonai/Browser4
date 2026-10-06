---
title: "Scrape Command Reference"
description: "Reference for the `scrape` and `scrape formats` commands. One request, one page capture, many output formats returned together in a single Firecrawl-compatible document."
tier: procedure
---

# Scrape Command Reference

Produce several outputs from a page in **one** request. `scrape --formats
markdown,links,images` loads the page once and returns markdown, the links and the
images together, all describing the same page state.

This is the difference from issuing one command per output: `htmlsnapshot get` and
`htmlsnapshot readability` each capture the page before they answer, so asking for
three outputs that way is three page loads and three possibly-different page states.
Here the capture is shared.

## Quick Start

```bash
browser4-cli scrape --formats "markdown,links"        # quote the comma list
browser4-cli scrape                                   # defaults to markdown
browser4-cli scrape --formats '["markdown","links"]'  # JSON array form
browser4-cli scrape formats                           # what this build can deliver
```

On an existing session, `scrape` needs no arguments beyond what you ask for:
it reads the page the session is already on.

## When to Use

Use `scrape` when you want:

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
| Several outputs from one page, once | **`scrape`** |

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
browser4-cli scrape --formats "markdown,links,images,readability"
```

**A format with its own options** (an object instead of a name):

```bash
browser4-cli scrape --formats '[{"type":"markdown"},{"type":"attributes","selectors":[{"selector":".price","attribute":"data-amount"}]}]'
```

**A deterministic extraction** — `deterministicJson` takes X-SQL, so the result is
reproducible rather than model-generated:

```bash
browser4-cli scrape --formats '["markdown",{"type":"deterministicJson","sql":"select dom_first_text(dom, \'h1\') as title"}]'
```

**Scrape a page other than the one you are on** — load it first; `scrape` always reads
the active page:

```bash
browser4-cli open "https://example.com/post"
browser4-cli scrape --formats "markdown"
```

**Discover before you ask** — `scrape formats` lists every accepted id, whether this
deployment can deliver it, and why not when it cannot:

```bash
browser4-cli scrape formats
browser4-cli scrape formats | grep -o '"id":"[a-zA-Z]*"'    # just the ids
```

**Checking what actually came back** — read `formatsDelivered`, not the presence of a
field:

```bash
browser4-cli scrape --formats "markdown,links" | grep -o '"formatsDelivered":\[[^]]*\]'
```

## Flags

| Flag | Meaning |
|---|---|
| `--formats <list>` | The outputs to produce, in request order. Accepts a comma-separated string (`"markdown,links"`), a JSON array (`'["markdown","links"]'`) or a single name. An entry may be an object carrying that format's options. Omitted or empty means `["markdown"]` |
| `--no-main-content` | Derive markdown from the whole cleaned page instead of the readable article. A flag, not a value: the default is already `true`, so only turning it off needs spelling |
| `-o`, `--output <dir>` | Fetch the files this request produced (`screenshot`, `pdf`) into `<dir>` and print the document with those local paths. Without it, a binary format can only report the backend's own path. The directory is created if it does not exist |
| `--strict` | Fail instead of degrading when a requested format is not delivered: non-zero exit, and the message names the code to act on. Without it the field is omitted, the reason goes in `warning`, and everything else still comes back |

`scrape` takes **no URL argument**, and that is deliberate rather than an omission.
Every read in this family targets the session's *active* page — `html_snapshot
export` does not even accept a URL — so a request-level URL could only be recorded
in `metadata.sourceURL` while the content came from whatever page the tab was
showing. It returned a plausible document about the wrong page. To scrape a
particular page, `open` it first:

```bash
browser4-cli open "https://example.com/post"
browser4-cli scrape --formats "markdown"
```

Per-request URL targeting arrives with Stage 0 (loading the page read-only on the
shared scrape session); until then the argument is refused rather than ignored.

`scrape formats` takes no flags. `sessionId` is not a flag you pass — the CLI adds it
to every call it makes.

**Available formats in a stock install** — `markdown`, `html`, `rawHtml`, `links`,
`images`, `attributes`, `deterministicJson`, `readability`, `screenshot`, `pdf`.
Everything else the command accepts (`json`, `summary`, `question`, `audio`, `video`,
`changeTracking`, `rawBase64`) is either a later phase or needs a plugin;
`scrape formats` is the authority for the build you are running, not this list.

`screenshot` and `pdf` are the two formats that need the **live tab**, and the only
two that write a file. It captures the visible viewport by default, or the whole scrollable
page with `--formats '[{"type":"screenshot","fullPage":true}]'`.

**The file is always written; the path is what comes back:**

```json
{"screenshot": "/…/cache/web/screenshot/screenshot-<ts>-<rand>.png"}
```

That path is on the machine running the **backend**, under the project's temporary
tree — usable from a CLI on the same host. There are two ways to get the bytes onto
*your* machine, and they suit different callers:

```bash
# 1. The CLI fetches them for you (the usual choice from a CLI)
browser4-cli scrape --formats "screenshot,pdf" --output ./out
# → the printed document points at ./out/…, and each file is also listed afterwards as
#   [Artifact](./out/<name>) so a script can read the destinations off the output

# 2. Fetch by name yourself, e.g. from another language or another host
curl -o shot.png "http://<backend>:18182/api/scrape/media/screenshot-<ts>-<rand>.png"
```

`--output` is the only way to get a binary format out of a scrape: `markdown` and the
other text formats go to stdout, but an image or a PDF cannot, so without `--output`
the document can only tell you where the *backend* put the file.

For the raw endpoint, only the file **name** is accepted, never a path. A string that
cannot be an artifact name at all (one containing `/` or `\`, or starting with a dot)
is refused with **400** rather than cleaned up, and a well-formed name that is not there
is **404** with the usual `{success, error, message}` body. Artifacts are temporary:
they live in the process temp tree and go away with it.

Ask for the bytes inside the document as well and it carries both:

```bash
browser4-cli scrape --formats '[{"type":"screenshot","fullPage":true,"base64":true}]'
# → "screenshot": "<path>", "screenshotBase64": "iVBORw0KGgo…"
```

The bytes are opt-in on purpose: base64 dwarfs everything else in the document.

Two screenshot options are **refused rather than ignored**, because no tool can
honour them and a silently wrong capture is worse than an error:

| Option | Why it is refused |
|---|---|
| `viewport: {width, height}` | `tab.screenshot`'s `viewport` is a *scroll index*, not a size — passing a size as an index would capture the wrong region and report success |
| `quality` | `tab.screenshot` has no quality setting |

Either one produces `screenshot: … is not supported yet — …` in `warning`, and the
rest of a mixed request still runs.

### `pdf`

Prints the page through CDP `Page.printToPDF` — A4, portrait, background graphics
printed — and comes back as a file, exactly like `screenshot`:

```json
{"pdf": "/…/cache/web/pdf/pdf-<ts>-<rand>.pdf"}
```

Note the directory: PDFs are filed under `web/pdf`, a sibling of `web/screenshot`, so
a folder name never contradicts what is inside it.

`pdf` is a **Browser4 extension**: Firecrawl has no page-to-PDF output format (its own
`pdf` code goes the other way — it *parses* a PDF you scraped into markdown). There is
therefore no Firecrawl option set to match, and an option that cannot be honoured is
refused by name rather than ignored:

| Option | Why it is refused |
|---|---|
| `viewport: {width, height}` | A PDF is printed for the whole document; `Page.printToPDF` has no region to capture |
| `quality` | The PDF path has no quality setting |

`fullPage` is accepted and changes nothing, which is deliberate: a PDF already *is* the
whole page, and `fullPage` is a plain boolean defaulting to `false` — so "the caller
said nothing" and "the caller said `false`" are the same value, and refusing it would
refuse every plain `"pdf"` request.

`base64: true` also returns `pdfBase64`, on the same terms as `screenshot`: the file is
written either way, so the bytes come *in addition to* the path, never instead of it.

```bash
browser4-cli scrape --formats '[{"type":"pdf","base64":true}]'
# → "pdf": "<path>", "pdfBase64": "JVBERi0xLjQ…"
```

### `--strict`: all or nothing

By default an undeliverable format is **omitted and named in `warning`**, and the rest of
the document still comes back. That is Firecrawl's behaviour and the right default for a
request that asks for several things.

`--strict` turns any such degradation into a failure:

| Situation | Default | `--strict` |
|---|---|---|
| A format id this build does not implement (`audio` today) | field omitted, `warning`, exit 0 | **503 `TARGET_UNAVAILABLE`** |
| A plugin contributor that is not installed (`branding`) | same | **503 `TARGET_UNAVAILABLE`** |
| A format that ran and failed (a live capture, a contributor that threw) | same | **502 `UPSTREAM_ERROR`**, or **504 `TIMEOUT`** when it was a timeout |
| An option this build refuses (`screenshot.quality`), or a retired id (`query`) | same | **400 `INVALID_ARGUMENT`** — dropping it makes the request work |

The split is about *who can fix it*: 503 means nothing about your request would help, 4xx
means it would. That is also what `retryable` carries on the REST face.

Every degraded format is named in the message, not only the first, so one round trip shows
everything that is wrong. When several formats degrade, the status is the **first one's, in
request order**.

The common case fails *before the page is loaded*: a `--strict` request for a format this
build does not implement never captures anything.

```bash
browser4-cli scrape --formats "audio,markdown"            # exit 0, markdown + a warning
browser4-cli scrape --formats "audio,markdown" --strict    # non-zero, names audio
```

A request that asks for **only** live formats does not capture at all: with no
snapshot-scoped step there is nothing to serialize into the page store, so
`metadata.captureId` is absent. That is capture-once working in the other direction.


`branding`, `product`, `menu` and `highlights` are **contributor** formats: they
exist only once a plugin provides them, and `scrape formats` reports
`"no plugin contributor installed"` until one is.

## Errors & Recovery

| Symptom | Meaning | Fix |
|---|---|---|
| `Unknown format 'markdwon'. Known formats: …` | A format id this build does not accept. Nothing was captured | Check the spelling against the printed list, or run `scrape formats` |
| `'sessionId' is required: …` | Called over raw HTTP without a session. `scrape` reads the page a session is already *on*, so it will not pick one for you — guessing would read a different session's page | `open` the page first and pass the `sessionId` it returns; the CLI injects one for you automatically |
| Only the first format came back, and the exit code was 0 | Your shell split the comma list: in PowerShell an *unquoted* `a,b,c` is three arguments, so `--formats` received only `a` | Quote it: `--formats "markdown,links,images"`. `formatsRequested` in the response shows what the server actually received |
| `unexpected positional argument` | `scrape` takes no URL: every read targets the active page | `open <url>` first, then scrape |
| `'url' is not supported yet` (REST/MCP callers) | A request-level URL was rejected by name rather than ignored — the reads would have answered from the active page | `open` the page first; per-request targeting needs Stage 0 |
| The field you asked for is absent | Either the format produced nothing, or it is unavailable here | Read `formatsDelivered`: listed means it ran; `warning` names the ones that did not and why |
| `TARGET_UNAVAILABLE` (with `--strict`) | A format this build cannot deliver — the message names it, and no change to the request would help | Drop the format, or install the plugin it names |
| `UPSTREAM_ERROR` / `TIMEOUT` (with `--strict`) | The format ran and failed; both are retryable | Retry, or drop `--strict` to get the other formats plus a warning |
| `warning` names a format you did want | That format is unavailable or failed; the rest still succeeded | Install the plugin named in the message, or drop the format |
| `Unsupported page method: scrape links` | A `tool call page_scrape` invocation with an unquoted list: `links` became part of the method name | Use `scrape`, or `tool call page_scrape --json '{"formats":["markdown","links"]}'` |

A request never fails because one *optional* format was unavailable — that is a
`warning` and the other formats are still returned. A request does fail when a format
it depends on cannot be produced at all, and the error keeps the original message.
