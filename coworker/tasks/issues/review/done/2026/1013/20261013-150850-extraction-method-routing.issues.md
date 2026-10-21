# Issues: extraction-method-routing

> **Source:** `20261013-150850-extraction-method-routing.issues.json` | **Date:** 20261013-150850 | **Mode:** dev

## Scenario Background

### Task

All **7 acceptance criteria completed successfully** against MockSite (`localhost:18080`) using only `./b4w.ps1` commands in a named session (`-s b4eval`).

| AC | Path exercised | Result |
|---|---|---|
| **AC1** | Interact → fresh snapshot → extract | ✅ Filled 3 text fields, 1 dropdown, 1 radio, 2 checkboxes, submitted, then extracted `#result-data` — returned exactly the entered values (`"firstName":"Ada"`, `"country":"de"`, `"contactMethod":"phone"`, …) |
| **AC2** | `htmlsnapshot get text "#productTitle"` | ✅ `4K OLED TV 55` |
| **AC3** | `htmlsnapshot get all text "[class*=\"product-title\"]"` | ✅ all **6** titles ("6 elements matched.", not just the first) |
| **AC4** | `htmlsnapshot query --sql @file` with `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')` | ✅ 6 correlated rows; title/price/URL aligned per card (B0E000001↔4K OLED TV 55↔$899.99 … B0E000006↔Wireless Mouse↔$24.99) |
| **AC5** | `eval --json --file` | ✅ `{documentTitle, url, counts:{buttons:2,links:3,forms:1,inputs:4}, headings:[5]}` — every value verified against the page |
| **AC6** | `extract` (LLM configured — DeepSeek) | ✅ prose path matched the page; `--schema` path returned clean structured JSON (`title/price/rating/features`) |
| **AC7** | `crawl --seed-file … --depth 0 --sql @file --format table --refresh` | ✅ 6 pages → 6 aligned rows in ~36 s |

Note: the task's `div[class*="product-card"]` selector is **correct for the rendered DOM** — the served HTML uses `<article class="product-card">`, but page JS rewrites them to `DIV.product-card` (verified: `div`→6 matches, `article`→0). A useful demonstration that these commands read the live DOM.

### Execution Context

**Key Commands:**

**Key decisions / workarounds:**
1. Run everything under `-s b4eval`. My very first `snapshot`/`eval` omitted `-s` and silently hit the shared **DEFAULT** session (another run's page) — I caught it because the snapshot content didn't match the URL I had just navigated to.
2. AC1's first submit **failed validation** ("Phone number is required for phone contact"), yet `wait --fn "…classList.contains('visible')"` reported `✓ Wait complete`. I only found the failure by re-checking the live DOM, then filled `#phone-number` + the required terms checkbox and resubmitted. Root-caused afterwards as a genuine CLI bug (Issue 1).
3. AC7's crawl needed ~36 s of polling; I used a 600 s budget.

**Temporary artifacts** (all under `.test-sessions/20261013T0154334271610Z/`): `ac4-listing-query.sql`, `ac5-live-dom.js`, `ac6-schema.json`, `ac7-seeds.txt`, `ac7-detail-query.sql`, plus `w-*.out`/`batch-fail.out` evidence files.

---

## Issues Found (5 issues)

### Issue 1: `wait` reports success and exits 0 when the condition times out (false positive in every mode)

**Severity:** Critical
**Category:** Reliability

#### Reproduction

In session b4eval on http://localhost:18080/ec/dp/B0E000002:
  ./b4w.ps1 -s b4eval wait "#definitely-not-here-42" --timeout 4000
  ./b4w.ps1 -s b4eval wait --text "zzz-not-on-page-zzz" --timeout 5000
  ./b4w.ps1 -s b4eval wait --url "**/never-matches-this-xyz" --timeout 2500
  ./b4w.ps1 -s b4eval wait --fn "false" --timeout 3000
Control (what the backend actually returns):
  ./b4w.ps1 -s b4eval tool call wait_for_selector --json '{"selector":"#productTitle","timeoutMillis":2000}'      -> 1992   (found, elapsed ms)
  ./b4w.ps1 -s b4eval tool call wait_for_selector --json '{"selector":"#definitely-not-here-42","timeoutMillis":2000}' -> -6    (not found)
  ./b4w.ps1 -s b4eval tool call wait_for_function --json '{"pageFunction":"false","timeoutMillis":1500}'        -> null   (condition never true)

#### Expected Behavior

Each command should block for at most --timeout, then fail with a non-zero exit code and a message naming what was awaited ("element #definitely-not-here-42 not found within 4000ms").

#### Actual Behavior

Every command blocked for the full timeout (measured: 5.2s / 3.8s / 2.5s of waiting after ~4.4s CLI baseline) and then printed a success line with exit code 0: `✓ Element found: #definitely-not-here-42`, `✓ Wait complete`, `✓ URL matched`. Machine mode is equally misleading: `wait "#nope-42" --timeout 1500 --json` -> `{"status":"ok","command":"wait","output":{"result":"-8"}}`, exit 0 — the failure is encoded only as an undocumented negative code, while the envelope says `ok`.

#### Root Cause Analysis

The backend already distinguishes outcomes (positive elapsed-ms on success, negative codes like -6/-8 on not-found/timeout, null for a never-true function/page condition). The CLI discards that information: `format_wait_result()` builds its message purely from the tool name and request params, and the call site treats any non-error HTTP round trip as success, so no exit code is ever set. This is the primary synchronization primitive the docs tell users to rely on ("poll the result element first (`wait '<result-selector>'`)"), so an unready page is silently read as ready. This is exactly what happened during AC1: `wait --fn` returned success while the form submission had actually failed validation.

#### Code Pointer

`cli/browser4-cli/src/main.rs:28885 format_wait_result() — call site cli/browser4-cli/src/main.rs:8236-8247; backend values observed via tool call wait_for_selector/wait_for_function/wait_for_page`

#### AI Suggested Improvement

- Inspect the returned value in the wait path: positive number = found (keep the elapsed ms in the message), negative code / null = timeout, and route that to the error path with a non-zero exit code.
- Translate the codes instead of echoing them: -6/-8 -> "element not found within <timeout>ms"; null -> "condition never became true within <timeout>ms".
- Make the JSON envelope honest: `"status":"ok"` must not accompany a failed wait — emit `"ok":false` plus a `reason`/`timedOut` field, and document the code mapping.
- Add regression tests asserting non-zero exit and a failure message for the four false-condition cases (selector, --text/--fn, --url, --load).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `batch` silently discards per-step output for Unit-returning tools and leaks a raw value for others

**Severity:** Medium
**Category:** UX

#### Reproduction

In session b4eval on the MockSite form page:
  ./b4w.ps1 -s b4eval batch --bail "fill e3 Ada" "fill e4 Lovelace" "fill e5 ada@example.com" "select e6 de" "check e12" "check e15"
  ./b4w.ps1 -s b4eval batch --bail "fill e7 +1 555 123 4567" "check e18"
Both runs did perform every operation (verified with eval), but see Actual.

#### Expected Behavior

A per-step result line (success/failure) for each sub-command so the caller can tell what ran, e.g. `✓ fill e3` / `✓ select e6 de` / `✓ check e18`.

#### Actual Behavior

The first batch printed a single stray line `["de"]` (the raw return value of `select`) and nothing for the other five steps. The second batch printed no output at all. Because a failing batch does exit non-zero (measured exit=5 with a clear error), silent output is indistinguishable from a silent no-op for a user reading stdout.

#### Root Cause Analysis

Server-side `handleBatchTool` normalizes blank tool output to null (`text.ifBlank { null }`), and the Rust renderer prints a step only when `text` is `Some`. `fill` and `check` return Kotlin `Unit` -> blank -> null -> nothing printed, while `select` returns `List<String>` which `ToolResultTextRenderer` Jackson-serializes, producing the bare `["de"]` line. Neither the batch help page nor the command description documents this output contract.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:975-977 handleBatchTool(); cli/browser4-cli/src/main.rs:26186-26201 render_batch_result() (print-only-when-Some at main.rs:26194)`

#### AI Suggested Improvement

- Emit a synthetic per-step status line in the CLI when the backend sends no text for a step (`✓ fill e7`, `✓ check e18`) instead of printing nothing.
- Stop leaking raw return values into the human stream: label them (`select e6 -> ["de"]`) or move them behind --json.
- Document batch's output contract in `browser4-cli help batch` (which currently documents only --bail and --json), so users know silence means success.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `list` URL column is always "-" because the backend never populates the session URL

**Severity:** Medium
**Category:** Product

#### Reproduction

  ./b4w.ps1 -s b4eval goto "http://localhost:18080/ec/dp/B0E000002"
  ./b4w.ps1 list
  ./b4w.ps1 list --verbose
  ./b4w.ps1 list --json

#### Expected Behavior

The URL column shows each session's current page URL, per the documented contract ("list shows every session and its current page URL … `-` means the backend is unreachable or the saved session is stale"), truncated to 48 chars, with --verbose/--json giving the full value.

#### Actual Behavior

Every row shows `-` for all four sessions, including `b4eval` with Status=Active, Connection=Browser4, Next open=Reuse, and a page loaded seconds earlier. `list --json` reports `"backend_reachable":true` and still `"url":"-"` on every session. Since the documented meaning of `-` is "backend unreachable or stale", a user reading the docs concludes the backend is down when it is healthy.

#### Root Cause Analysis

`ManagedSession.url` is declared as `var url: String? = null` and never assigned anywhere in the backend — `createManagedSession()` and every navigation/health path omit it. `handleListSessions()` therefore serializes `s.url ?: ""`, and the CLI's `unwrap_or_else(|| "-")` renders the empty string as `-`.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/ManagedSession.kt:22 (field never written); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:689 handleListSessions(); cli/browser4-cli/src/main.rs:5157 (URL fallback)`

#### AI Suggested Improvement

- Populate `url` where the session is touched — cheapest correct point is `handleListSessions`/health-check, using `managed.driver.currentUrl()` (the same call `HTMLSnapshotToolExecutor.kt:394` already makes) so stale/never-written state can't drift.
- Alternatively remove the field and the column, and update SKILL.md §2 and `help list`, so the documentation stops promising a value that cannot appear.
- Whichever path is chosen, make `-` mean one thing: reserve it for genuinely unknown values, not for "implemented but never filled".

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: No session identity in command output — omitting -s silently operates on another run's default session

**Severity:** Medium
**Category:** UX

#### Reproduction

With a DEFAULT session already open on an unrelated page:
  ./b4w.ps1 -s b4eval goto "http://localhost:18080/generated/form-filling.html"
  ./b4w.ps1 snapshot -i -v 0 --stdout     # note: no -s
  ./b4w.ps1 eval "document.title"          # note: no -s

#### Expected Behavior

Either the output identifies the session it came from, or the CLI warns that no session was specified while several exist.

#### Actual Behavior

The snapshot and eval ran silently against the shared DEFAULT session, whose page was an unrelated MockSite product page from a previous run. Nothing in the output distinguished it from the b4eval page I had just navigated to; the snapshot rendered a plausible e-commerce tree (links, 'Add to cart' buttons) that looked like a legitimate result. I detected the mix-up only by comparing `eval`'s `location.href` with the URL `goto` reported. The trap is spelled out in SKILL.md §2, but the CLI gives no runtime signal, and `snapshot`/`eval`/`htmlsnapshot` headers carry no session name (unlike `open`, which prints `Session opened: <name>`).

#### Root Cause Analysis

The snapshot/eval/htmlsnapshot renderers print page metadata and the artifact path but never the session name, and unset `-s` falls back to the singleton DEFAULT session by design.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6749 (snapshot stdout header) — session label exists elsewhere (main.rs:2991) but is not applied to snapshot/eval/htmlsnapshot output`

#### AI Suggested Improvement

- Add a `Session: <name>` line to the snapshot/page-info/htmlsnapshot headers and to the `### Page` block every navigation-triggering command prints, so every result is self-identifying.
- Warn on stderr when a command runs without `-s`/`--session` while more than one session is active ("using DEFAULT session — pass -s <name> to target another"), mirroring the existing `goto`/`open` advisories.
- Include the session name in the `--json` envelopes of snapshot/eval/htmlsnapshot so scripted runs can assert it.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `extract` is described as returning structured data, but without --schema it returns a fixed prose envelope

**Severity:** Low
**Category:** Documentation

#### Reproduction

On http://localhost:18080/ec/dp/B0E000002:
  ./b4w.ps1 -s b4eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout
  # vs. the structured path:
  ./b4w.ps1 -s b4eval extract "Extract the product details" --schema "@.test-sessions/20261013T0154334271610Z/ac6-schema.json" --stdout

#### Expected Behavior

The instruction explicitly asks for JSON, so structured output was expected — or the help text should state that structured output requires --schema.

#### Actual Behavior

The plain path returned a fixed envelope with the answer as prose: `{"title":"Wireless Noise-Cancelling Headphones","content":"Product title: …\nDisplayed price: $199.99\nRating: 4.4 (312 reviews)\nTop feature bullets:\n1. Bluetooth 5.2\n2. 30h battery","links":[…]}` — the "as JSON" instruction had no effect. Only with `--schema` did it emit top-level fields (`{"title":…,"price":"$199.99","rating":"4.4","features":["Bluetooth 5.2","30h battery"]}`). Note the content itself was accurate (the page genuinely has only two feature bullets, so "top three" could not be met) — this is purely an output-shape/documentation gap.

#### Root Cause Analysis

`extract`'s non-schema path always renders the `{title, content, links}` envelope; the command description says "Extract structured data from the current page" without mentioning that `--schema` is what actually produces structured fields, and SKILL.md's Output Modes section describes only the file-vs-stdout behaviour.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3479 (command description) — help text in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Reword the help line to "Extract data from the current page; pass --schema for structured JSON fields (otherwise a title/content/links envelope is returned)" and document the envelope itself.
- Detect a JSON-shaped request in the instruction ("as JSON", "return fields") when `--schema` is absent and print a one-line stderr hint suggesting `--schema`.
- Show the envelope shape in the `extract` examples so the default output is not a surprise.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 7 acceptance criteria met (interact-then-extract, single field, get all, correlated X-SQL rows, eval --json, natural-language extract, and seed-file crawl). One AC1 retry was needed after a form-validation failure that the CLI's `wait` reported as a success.

**Success Rate:** 95% — all 7 ACs produced correct, verified output; the only lost time was the silent `wait` false positive in AC1 and a self-inflicted session mix-up from omitting -s.

**Issues Found:** 5

**Major Blockers:** No hard blockers. One critical reliability defect (`wait` always reports success) undermines the documented synchronization workflow but can be worked around by verifying state with `eval`/`get` instead of trusting `wait`.

**Most Confusing Aspects:** (1) `wait` printing '✓ Element found' / '✓ URL matched' for conditions that never became true — I trusted it and read a stale form result as the submission result. (2) Omitting `-s` silently routes a command to the shared DEFAULT session, and no command output names the session, so a plausible-looking snapshot was actually another run's page. (3) `batch` printing nothing on success and a raw `["de"]` for one step. (4) `list` advertising a URL column that can never be populated, with `-` documented to mean 'backend down'.

**Most Valuable Improvements:** Fix the `wait` exit-code/verdict logic first (highest impact: it is the documented synchronization primitive and currently cannot fail). Then label every result with its session name and warn when -s is omitted while other sessions exist. Then give `batch` per-step status lines, and either populate the session URL or drop the column and its documentation promise.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: `wait` reports success and exits 0 when the condition times out (false positive in every mode)

In session b4eval on http://localhost:18080/ec/dp/B0E000002:
  ./b4w.ps1 -s b4eval wait "#definitely-not-here-42" --timeout 4000
  ./b4w.ps1 -s b4eval wait --text "zzz-not-on-page-zzz" --timeout 5000
  ./b4w.ps1 -s b4eval wait --url "**/never-matches-this-xyz" --timeout 2500
  ./b4w.ps1 -s b4eval wait --fn "false" --timeout 3000
Control (what the backend actually returns):
  ./b4w.ps1 -s b4eval tool call wait_for_selector --json '{"selector":"#productTitle","timeoutMillis":2000}'      -> 1992   (found, elapsed ms)
  ./b4w.ps1 -s b4eval tool call wait_for_selector --json '{"selector":"#definitely-not-here-42","timeoutMillis":2000}' -> -6    (not found)
  ./b4w.ps1 -s b4eval tool call wait_for_function --json '{"pageFunction":"false","timeoutMillis":1500}'        -> null   (condition never true)

#### Issue 2: `batch` silently discards per-step output for Unit-returning tools and leaks a raw value for others

In session b4eval on the MockSite form page:
  ./b4w.ps1 -s b4eval batch --bail "fill e3 Ada" "fill e4 Lovelace" "fill e5 ada@example.com" "select e6 de" "check e12" "check e15"
  ./b4w.ps1 -s b4eval batch --bail "fill e7 +1 555 123 4567" "check e18"
Both runs did perform every operation (verified with eval), but see Actual.

#### Issue 3: `list` URL column is always "-" because the backend never populates the session URL

  ./b4w.ps1 -s b4eval goto "http://localhost:18080/ec/dp/B0E000002"
  ./b4w.ps1 list
  ./b4w.ps1 list --verbose
  ./b4w.ps1 list --json

#### Issue 4: No session identity in command output — omitting -s silently operates on another run's default session

With a DEFAULT session already open on an unrelated page:
  ./b4w.ps1 -s b4eval goto "http://localhost:18080/generated/form-filling.html"
  ./b4w.ps1 snapshot -i -v 0 --stdout     # note: no -s
  ./b4w.ps1 eval "document.title"          # note: no -s

#### Issue 5: `extract` is described as returning structured data, but without --schema it returns a fixed prose envelope

On http://localhost:18080/ec/dp/B0E000002:
  ./b4w.ps1 -s b4eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout
  # vs. the structured path:
  ./b4w.ps1 -s b4eval extract "Extract the product details" --schema "@.test-sessions/20261013T0154334271610Z/ac6-schema.json" --stdout

