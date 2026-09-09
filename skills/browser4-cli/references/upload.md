---
title: "Upload — Send Local Files to a File Input"
description: "Reference for the upload command. Upload one or more local files to a page <input type=\"file\"> element from a snapshot ref or CSS selector."
tier: procedure
---

# Upload — Send Local Files to a File Input

`upload` uploads one or more local files from disk into a page's file picker element — `<input type="file">`. It is the counterpart of `fill`/`type` for file upload controls: instead of typing a path, the real files are attached through CDP.

## Quick Start

```bash
browser4-cli goto "https://example.com/upload"
browser4-cli snapshot -i -v 0          # find the file input ref
browser4-cli upload e12 /abs/path/resume.pdf /abs/path/cover.pdf
```

- `upload <ref> <file> [file...]` — the ref (snapshot ref like `e12`, or CSS selector) must point at an `<input type="file">`; every following argument is one file to upload together.
- Absolute paths are recommended. Files must be **readable by the browser process on the machine running the browser** — in local mode that is your machine; against a **remote backend** the paths are resolved on the backend host, not on your machine.
- Multi-file inputs receive all listed files in one shot.

## When to Use

Use **upload** whenever a workflow needs to attach a file to a page — avatar/photo uploads, document submission, CSV/Excel imports, "choose file" inputs behind a visible button. If the page only shows a button (no obvious input), find the hidden `<input type="file">` in the snapshot with `snapshot grep "file"` or via a CSS selector like `input[type=file]`.

## How It Works

The backend driver resolves the target through `DOM.describeNode`, verifies it really is a file input, then calls `DOM.setFileInputFiles` with the element's stable `backendNodeId` — no key synthesis, no OS file dialogs.

The target must be a file input:

```text
upload: the target [...] is a <...>, not a file input. ... only file inputs accept uploads.
```

In **local mode** the CLI rejects empty files and non-existent paths up front with a clear hint. Against a remote backend, path checks happen on the backend host, where `DOM.setFileInputFiles` also requires absolute paths readable by the browser process.

## Usage

```bash
# Single file, ref target
browser4-cli upload e5 /home/me/invoice.pdf

# CSS selector target
browser4-cli upload "#file-input" C:\docs\invoice.pdf

# Multiple files at once (multi-select input)
browser4-cli upload e5 /data/photo-1.jpg /data/photo-2.jpg /data/photo-3.jpg

# Skip the automatic post-action accessibility snapshot (saves a round-trip)
browser4-cli upload e5 /tmp/export.csv --no-snapshot
```

> **Shell quoting:** on PowerShell, quote paths starting with `@` and wrap paths containing spaces: `browser4-cli upload e5 "C:\My Docs\resume.pdf"`.

### Flags

| Option | Description |
|--------|-------------|
| `--no-snapshot` | Skip the automatic post-command accessibility snapshot (interaction commands capture one by default) |

## Patterns

### Typical agent flow

```bash
browser4-cli snapshot -i -v 0          # refs for the form (e.g. e5 = file input)
browser4-cli upload e5 /tmp/cv.pdf     # attach the file
browser4-cli click e9                  # submit the form
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff # verify upload result
```

### Re-verify after upload

Uploading can change the page (file name chips, previews, enabled submit buttons). Re-snapshot and check with `snapshot grep "<expected filename>"` before proceeding — and remember refs are ephemeral, so use fresh refs after any interaction.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `upload requires a target ref and at least one file path` | Command form wrong | Use `upload <ref> <file> [file...]` |
| `... is a <div/button/...>, not a file input` | Ref/selector points at a non-file element | Target the `<input type="file">` (hidden inputs still work); use `snapshot grep file` or `input[type=file]` |
| File rejected as empty / not found (local mode) | Path invalid or file is empty | Point at an existing non-empty file; the CLI hint names the offending path |
| `DOM.setFileInputFiles requires absolute paths ...` | Relative or unreadable path (especially remote backend) | Pass absolute paths that the **browser/backend host** can read |
| Nothing visibly happens | Page shows a styled "Upload" button wrapping the hidden input | Uploading still worked if the input was targeted; verify via snapshot grep for the file name |

## See Also

- [snapshot.md](snapshot.md) — discover file-input refs (`-i`/`-v 0`, `snapshot grep`)
- [fill/type usage in SKILL.md](../SKILL.md#6-quick-patterns) — sibling interaction commands
