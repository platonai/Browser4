# /review-issues — Interactive Issue Review Skill

Activate when the user types `/review-issues`, "review issues", or asks to review/evaluate/approve/reject the issue files from the coworker review directory. This skill guides the user through reviewing `.issues.md` files one at a time, collecting batch decisions per file, and writing checkboxes back.

## Prerequisites

- Review files live at: `coworker/tasks/200issues/review/` (scanned recursively for `*.issues.md`)
- Each issue has a **Human Review** block with 5 checkbox options plus a **Notes:** field
- Some files may already have `[x]` or `[ ✅ ]` marks from prior reviews

## Phase 1: Scan & List

Scan all `*.issues.md` files recursively under `coworker/tasks/200issues/review/`. For each file, count:
- **Total issues**: Count `### Issue N:` headings
- **Reviewed issues**: Count issues that have `[ ✅ ]` in any Human Review checkbox (never count `[ ]`)
- **Status**: 
  - ⬜ **empty** — 0 issues total
  - ⬜ **unreviewed** — 0 of N reviewed
  - 🔄 **partial** — some but not all reviewed
  - ✅ **done** — all issues reviewed

Present as a numbered table, sorted by status (unreviewed first, then partial, then done/empty last):

```
#  File                                    Issues  Reviewed  Status
1  amazon                                  7       0         ⬜ unreviewed
2  agent-extraction                        5       0         ⬜ unreviewed
3  htmlsnapshot-evaluation                 8       7         🔄 partial
4  navigation-basics                       0       0         ⬜ empty
5  form-filling                            3       3         ✅ done
```

After the table, prompt: "Pick a file (number, name, or `next` for first unreviewed):"

## Phase 2: Read & Present

Read the selected `.issues.md` file. Extract these fields from each issue:
- **Issue number** (from `### Issue N:`)
- **Severity** (Critical / High / Medium / Low)
- **Category** (Product / UX / Documentation / etc.)
- **Title** (the heading text after `### Issue N:`)
- **One-line summary** — the first sentence of Reproduction or the key problem statement
- **Key suggestion** — first bullet of "AI Suggested Improvement" or the most actionable fix
- **Already reviewed?** — check if `[ ✅ ]` exists in the Human Review block

Present each issue as a compact card. Example format:

```
## amazon — 7 issues (0 reviewed)

### Issue 1 [Medium | UX] Auto-discover defaults to generic selectors
> `htmlsnapshot inspect` analyzes navigation elements first, missing product cards.
  → Prioritize containers with product-card patterns (image+heading+price).

### Issue 2 [Medium | Documentation] CSS selectors fail on non-English locale
> `h2 a.a-link-normal` returns empty on Chinese Amazon; needs `s-line-clamp-4` class.
  → Add locale-variant selectors to scenario docs.

### Issue 3 [Low | Product] Snapshot is YAML-only, no JSON option
> `snapshot` command lacks `--json` flag for machine-readable output.
  → Add `--json` output format.

### Issue 4 [Low | UX] Relative SQL file path resolution confusing
> `@../../query.sql` paths resolve relative to CWD, not intuitive.
  → Document path resolution or add `--sql-file <absolute>`.

### Issue 5 [Low | Product] No built-in review-count extraction
> No consistent selector for Amazon review counts across result types.
  → Enhance `inspect` to detect numeric patterns near ratings.

### Issue 6 [Low | UX] htmlsnapshot output too verbose
> 100 interactive elements listed equally; hard to find key info.
  → Group by section, add `--summary` flag.

### Issue 7 [Medium | Discoverability] Viewport 0 truncation loses results
> Products below the fold; `snapshot -v 0` shows only header/nav.
  → Auto-detect search pages, suggest higher viewports.
```

For issues already reviewed, show the existing decision inline:
```
### Issue 3 [Low | Product] Snapshot is YAML-only  [ already: REJECT ]
```

## Phase 3: Collect Decisions

After presenting all issues, show the decision prompt:

```
---
**Decisions** (one per issue, comma or newline separated):

  <N> <CODE> [optional notes]

  Codes:  a=ACCEPT  a+=ACCEPT+  d=DEFER  w=WONTFIX  r=REJECT  dup=DUPLICATE

Examples:
  1 a, 2 r not a real bug, 3 d too minor for now, 4 a+, 5 ACCEPT, 6 REJECT
  skip    (skip this file entirely)
```

**Rules:**
- Only prompt for issues that are NOT already reviewed. Skip already-reviewed issues.
- Accept any of: short codes (a/a+/d/w/r), full names (ACCEPT, ACCEPT with improvements, DEFER, WONTFIX, REJECT), or case variants.
- Notes are extracted from the text after the decision code/word, up to the next comma or issue number.
- If the user types `skip`, abort this file and return to Phase 1.
- If the user types `all a` or similar, apply the same decision to all unreviewed issues.

## Phase 4: Parse

Parse the user's response using these rules:

1. Split on commas, newlines, or numbered prefixes (`1.`, `2)`, etc.)
2. For each segment, extract: `(issue_number) (decision_word) [optional_notes]`
3. Map decision words (case-insensitive):
   - `a`, `accept` → **ACCEPT**
   - `a+`, `accept+`, `accept with improvements` → **ACCEPT with improvements**
   - `d`, `defer` → **DEFER**
   - `w`, `wontfix` → **WONTFIX**
   - `r`, `reject` → **REJECT**
   - `dup`, `duplicate` → **DUPLICATE**
4. Notes = everything after the decision word until next comma/issue boundary, trimmed
5. Validate: issue number must exist and not already be reviewed

Handle `all <code>` as a special case — apply the same decision to every unreviewed issue.

## Phase 5: Confirm

Display a confirmation table before writing:

```
| # | Decision              | Notes             |
|---|-----------------------|-------------------|
| 1 | ACCEPT                | —                 |
| 2 | REJECT                | not a real bug    |
| 3 | DEFER                 | too minor for now |
| 4 | ACCEPT with improvements | —              |
| 5 | ACCEPT                | —                 |
| 6 | REJECT                | —                 |
| 7 | ACCEPT                | —                 |

Write these decisions to amazon.issues.md? (y/n/edit)
```

Wait for user confirmation. If "edit", let the user restate their decisions.

## Phase 6: Write Back

For each decided issue, use the **Edit** tool on the `.issues.md` file:

1. **Locate the Human Review block** for issue N:
   - Search the file for the pattern `### Issue N:` 
   - Then find the nearest `**Human Review:**` or `#### Human Review` block that follows

2. **Mark the checkbox**: Find the line matching the chosen decision (e.g., `- [ ] **ACCEPT**`), replace `[ ]` with `[x]`:
   ```
   OLD: - [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
   NEW: - [ ✅ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
   ```

3. **Add notes** (if any): Notes go on the line IMMEDIATELY AFTER `**Notes:**` (this matches existing convention in reviewed files like `htmlsnapshot-evaluation.issues.md`):
   ```
   OLD: - **Notes:**
   
   NEW: - **Notes:**
   <note text on the next line>
   ```
   Example result:
   ```
   - **Notes:**
   too minor for now
   ```
   If `**Notes:**` already has content on the next line, append with a `; ` separator on the same line.

4. **Skip already-reviewed** issues — do not modify them. If the user wants to re-review, they must explicitly request it.

**Important Edit tool rules:**
- The `old_string` must match exactly, including indentation and dashes
- Always use `[ ✅ ]` (checkmark emoji) for reviewed items — this is the project convention
- If the file uses `#### Human Review` (h4) vs `**Human Review:**` (bold), match the format used in that specific file — the two formats coexist across different files (see examples below)

### File format variants

Files may use different Human Review block formats. Always match the format used in the specific file:

**Format A** (used in most `.issues.md` files like `amazon.issues.md`):
```
#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
```

**Format B** (used in files like `htmlsnapshot-evaluation.issues.md`):
```
**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ✅ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
```

When matching `old_string` for Edit, read the actual lines from the file to preserve exact formatting.

## Phase 7: Loop

After writing, confirm: "Done! amazon.issues.md updated (7/7 reviewed)." Return to Phase 1 (show updated list) so the user can pick the next file.

## GUI Alternative

The Issue Review SPA at `http://127.0.0.1:8090/issues/review` provides a visual interface for the same workflow:

- **One-by-one** or **all-issues** view modes
- **AI Review** button (calls Claude for a suggested decision)
- **Mark Done** — creates summary in `1ready`, archives original to `review/done`
- **Discard** — moves valueless files (no issues / no valuable issues) to `review/done/discard/`
- **Auto-approve checkbox** — appends `#auto-approve` tag to the summary so the coworker pipeline auto-routes to `5approved` and triggers git push
- Keyboard shortcuts: `Shift+1`–`Shift+5` (decisions), `Shift+A` (AI review), `Shift+D` (discard), `Shift+N`/`Shift+P` (prev/next file), `Ctrl+S` (save)

## Quick Reference: Decision Codes

| Code | Meaning |
|------|---------|
| `a` | ACCEPT — issue valid, fix is correct |
| `a+` | ACCEPT with improvements — issue valid, fix needs refinement |
| `d` | DEFER — acknowledged but intentionally postponed |
| `w` | WONTFIX — acknowledged but will not be fixed |
| `r` | REJECT — issue invalid, not a problem, or already addressed |
| `dup` | DUPLICATE — issue duplicates another existing issue (reference in Notes) |

## Edge Cases

| Case | Behavior |
|------|----------|
| File has 0 issues ("No issues could be parsed") | Show as "⬜ empty", skip |
| File already fully reviewed | Show as "✅ done", ask if re-review desired |
| User types "skip" during decision phase | Abort this file, no writes, return to list |
| User types "all r" | Apply REJECT to all unreviewed issues, confirm before writing |
| Issue with missing Human Review block | Report "Issue N: no Human Review block found", skip that issue |
| File has mixed checkbox formats (some `[ ]`, some `[ ✅ ]`) | Only replace `[ ]` — never touch `[ ✅ ]` |
| Notes field already populated | Append with `; ` separator |
| User wants to change an already-reviewed decision | Warn, ask explicit confirmation, then overwrite |
| Malformed file (duplicate issue numbers) | Report the anomaly, ask user how to proceed |

## Example Interaction

```
User: /review-issues

Claude: [scans files, prints table]
  #  File                              Issues  Reviewed  Status
  1  amazon                            7       0         ⬜ unreviewed
  2  agent-extraction                  5       0         ⬜ unreviewed
  ...
  Pick a file (number, name, or `next`):

User: 1

Claude: [reads amazon.issues.md, presents 7 compact issue cards, then prompt]
  ---
  Decisions:

User: 1 a, 2 r known limitation, 3 d revisit in 4.12, 4 a+, 5 a, 6 r, 7 a

Claude: [shows confirmation table]
  | # | Decision              | Notes            |
  |---|-----------------------|------------------|
  | 1 | ACCEPT                | —                |
  | 2 | REJECT                | known limitation |
  | 3 | DEFER                 | revisit in 4.12  |
  | 4 | ACCEPT with improvements | —             |
  | 5 | ACCEPT                | —                |
  | 6 | REJECT                | —                |
  | 7 | ACCEPT                | —                |
  Write these decisions? (y/n/edit)

User: y

Claude: [writes checkboxes via Edit tool]
  Done! amazon.issues.md updated (7/7 reviewed).
  [returns to file list]
```
