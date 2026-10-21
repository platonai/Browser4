# Issues: crawl-link-options

> **Source:** `20261013-141727-crawl-link-options.issues.json` | **Date:** 20261013-141727 | **Mode:** dev

## Scenario Background

### Task

All four acceptance criteria passed against the live MockSite fixtures (`http://localhost:18080/generated/crawl/`). The backend (dev mode, `http://127.0.0.1:8283`) auto-started on the first command with no manual setup.

- **AC1 — depth 0 (fetch only): ✅** `./b4w.ps1 crawl ".../crawl/index.html" --depth 0 --refresh` completed in 3.4 s with `Crawl completed. 1 page found.` and the row `depth=0 | .../index.html | Crawl Test Hub`. No Widget Alpha content, no link discovery. Exit 0.
- **AC2 — depth 2 + selector + pattern: ✅** `crawl <url> -d 2 -ol "a.product" -olp "/product/"` returned exactly the 10 expected pages: hub (d0), Widget Alpha/Beta/Gamma (d1), Widget Delta/Epsilon/Zeta/Lambda/Mu/Nu (d2). No category pages (the `class="category-link"` anchors were correctly not followed) and no `/depth3/` pages (filtered by the pattern). This command **failed via `./b4w.ps1`** — Git Bash mangled `/product/` into a Windows path and the wrapper's guard rejected it (by design, with clear guidance) — and passed via the documented `./b4w.sh` wrapper.
- **AC3 — depth 3: ✅** 15 pages across depths 0–3 in 2 m 02 s, including all five depth=3 "Deep Widget" pages (Theta, Iota, Kappa, Lambda Prime, Mu Pro) plus every depth-1/2 intermediate page. Exit 0.
- **AC4 — seed file: ✅** `crawl --seed-file <run-dir>/seed-urls.txt --depth 0 --refresh` reported `URLs: 2` and fetched exactly Widget Alpha + Widget Gamma — no Widget Beta, no hub page. Exit 0.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md` and `references/crawl.md`; verified MockSite up (`curl :18080` → 200).
2. AC1 crawl → 1 page (daemon + backend auto-started).
3. AC2 attempted via `./b4w.ps1` → MSYS-guard exit 1; re-ran via `./b4w.sh` → 10 pages.
4. AC3 crawl → 15 pages incl. depth-3.
5. Wrote seed file in `.test-sessions/20261013T0154334271610Z/seed-urls.txt`; AC4 crawl → 2 pages.
6. Probes: `crawl --help` / `--help crawl` / `--help --examples`; `crawl <url> --depth 2 --refresh` **without** `-ol` (printed an explicit "Link discovery disabled (no --out-link-selector)" note — good); `crawl list`; `crawl status <id>`; `--background` submission + `status` while Processing + `crawl cancel` (clean lifecycle, checkpoint/resume hint surfaced).
7. Root-cause source reading: `cli/browser4-cli/src/main.rs`, `daemon.rs`, `commands.rs`; `browser4-rest/.../MCPToolController.kt`, `CrawlService.kt`; confirmed `/mcp/tools/specs` returns `{"tools":[]}` in 3 ms on the healthy server.

**Decisions / workarounds:** the only workaround needed was switching to `./b4w.sh` for the leading-`/` regex value (the guard message explicitly directs there). The background probe crawl was cancelled afterward to leave a clean task store. This run's only scratch artifact is the AC4 seed file.

**Notable positives:** clear 10-second progress reporting, depth-labelled result rows, explicit "link discovery disabled" warning, helpful "Use --background" hint, and a genuinely good fail-fast error for mangled Git Bash arguments.

---

---

## Issues Found (4 issues)

### Issue 1: Leading-'/' flag values (e.g. -olp "/product/") cannot run through ./b4w.ps1 from Git Bash — the CLI's own crawl example fails on the platform's default shell

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

From Git Bash in the repo root, run the exact command from `crawl --help` Examples:
./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"

#### Expected Behavior

The command runs (as it does via ./b4w.sh), or the crawl help/warning surfaces the required wrapper/caveat at the point of use.

#### Actual Behavior

Exit 1: "Error: argument 'C:/Program Files/Git/product/' (probably typed as '/product/') was rewritten by Git Bash's MSYS path conversion ... Run this command via ./b4w.sh instead (it disables the conversion)." The command succeeds only via ./b4w.sh (10 pages fetched, AC2 verified).

#### Root Cause Analysis

Git Bash/MSYS2 rewrites '/' -leading argv tokens into Windows paths before pwsh starts, and the original value is unrecoverable; b4w.ps1's guard (lines ~97–144) correctly detects and refuses it. The gap is disclosure: `crawl --help` shows the very example `-olp "/product/"` with no platform note, and this caveat is documented only in the bundled skill reference (skills/browser4-cli/references/crawl.md § Git Bash/MSYS2 caveat), which a user consulting `--help` never sees. The CLI itself cannot fix the conversion — only surface it.

#### Code Pointer

`b4w.ps1:97-144 (MSYS mangled-argument guard); cli/browser4-cli/src/commands.rs:3862 (crawl OptionDefs incl. --out-link-pattern) and the crawl built-in examples block`

#### AI Suggested Improvement

- Add a one-line platform note next to --out-link-pattern in `crawl --help` and the `--help --examples` output: "Git Bash: values starting with '/' are path-converted — use ./b4w.sh, MSYS2_ARG_CONV_EXCL='*', or omit the leading slash (e.g. -olp "product/")".
- Have the b4w.ps1 guard echo the exact de-converted command to run (e.g. MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 <original argv>) to shorten the recovery loop.
- Mention ./b4w.sh as the Git Bash entry point in the top-level `help` output (the environment's own instructions needed the CLAUDE.md to learn this).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `--help --examples` always prints "is the backend running?" on a healthy backend (that endpoint is normally empty)

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 status                          # Server health: UP
./b4w.ps1 crawl --help --examples
curl -s http://127.0.0.1:8283/mcp/tools/specs   # -> { "tools" : [ ] } in ~3 ms

#### Expected Behavior

Either the tool-level examples are printed, or the note states the real reason (this backend advertises no tool-level examples).

#### Actual Behavior

"Tool-level examples are unavailable: is the backend running?" while the same CLI at the same moment is successfully running crawls against that backend.

#### Root Cause Analysis

build_tool_examples() maps an *empty* spec list to Err("is the backend running?") (main.rs:21333-21335), but GET /mcp/tools/specs only returns specs whose cliName is set (MCPToolController.kt:639, filter `!it.cliName.isNullOrBlank()`); in this checkout only the optional browser4-profile-import plugin declares cliName, so the list is always empty on a stock install — the message is therefore permanently wrong. Compounding it, fetch_tool_spec_values_within() (main.rs:20996) collapses unreachable/timeout/HTTP-error/malformed-body into the same empty Vec, so the CLI cannot tell 'down' from 'no specs'.

#### Code Pointer

`cli/browser4-cli/src/main.rs:21327 build_tool_examples() / 20996 fetch_tool_spec_values_within(); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:639 listCliToolSpecs()`

#### AI Suggested Improvement

- Distinguish 'fetch failed' from 'backend answered but advertises no specs': return a reason from the fetch and print "the backend advertises no tool-level examples" for the empty case.
- Make the endpoint (or another one) serve specs for built-in tools too, so --help --examples can deliver what its name promises for e.g. crawl/click.
- Keep the error-flavored wording only for genuine connection failures; when built-in examples printed fine, demote the note to informational.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `-a, --args` documented as `<json>` but parsed as a raw LoadOptions string

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl --help   # option line: '-a, --args <json>  Additional LoadOptions passthrough...'  — while the Notes block on the same page says: 'passes additional LoadOptions through as a raw string (e.g. -a "-nMaxRetry 5")'; references/crawl.md shows -a "-requireSize 100000 -scrollCount 5"

#### Expected Behavior

The type placeholder matches what the value actually is.

#### Actual Behavior

The value is split on whitespace and forwarded verbatim as LoadOptions tokens (resolve_crawl_args_fallible, main.rs:16645-16666; commands.rs:3958 'Append raw args passthrough'). A user trusting `<json>` would send JSON text and get it forwarded as raw options; the docs contradict themselves within one help page.

#### Root Cause Analysis

Stale OptionDef label: commands.rs:3862 declares `name: "args <json>"` although the passthrough implementation and every example use a raw whitespace-separated options string.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3862`

#### AI Suggested Improvement

- Rename the placeholder to `--args <opts>` (or `<load-options>`) and state the raw form with an example in the option description itself.
- If JSON was the intended contract, implement/validate it; otherwise reject JSON-looking values with a hint so the mistake is loud, not silent.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: crawl status records: `createdAt` silently becomes the finish time on terminal tasks (and is epoch-millis while sibling fields are ISO-8601)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 3 --refresh -ol "a.product" --background
./b4w.ps1 crawl status <id>    # while Processing:  createdAt=1791900944936 == startedTime 14:15:44.936
./b4w.ps1 crawl cancel <id>
./b4w.ps1 crawl status <id>    # terminal:         createdAt=1791900988787 == finishTime 14:16:28.782 (43 s later)

#### Expected Behavior

`createdAt` is the stable task-creation instant — the code's own comment says it is task identity/age and drives the TTL purge (CrawlSupport.kt:1040-1042).

#### Actual Behavior

The running record's createdAt equals the start instant, but every terminal record overwrites it with the completion instant (verified on completed and cancelled tasks; exact millisecond match with finishTime). It is also emitted as raw epoch millis, unlike startedTime/finishTime/fetchedAt which are ISO-8601 strings.

#### Root Cause Analysis

The terminal CrawlResponse builders in CrawlService.kt (completed ~1381, timedOut ~1531, failed ~1589, cancelled ~1688) set startedTime/finishTime but omit `createdAt`, so the data-class default `createdAt: Long = System.currentTimeMillis()` (CrawlModels.kt:133) evaluates at finalization. Only mergeIncrementalProgress carries it forward, so the value visibly changes meaning at completion.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlService.kt (terminal CrawlResponse builders) and CrawlModels.kt:133`

#### AI Suggested Improvement

- Pass `createdAt = previous?.createdAt ?: <task registration instant>` explicitly in every terminal builder, or capture it once when the task is registered and thread it through.
- Emit it as ISO-8601 like the sibling timestamp fields for consistency in the machine-readable record.
- Add a regression test asserting createdAt is identical while Processing and after completion.

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

**Completion Status:** Successful — all four acceptance criteria (AC1–AC4) verified end-to-end against the live MockSite crawl fixtures with the auto-started dev backend.

**Success Rate:** 100% of acceptance criteria (4/4); at the command level, 1 of ~14 invocations needed the documented ./b4w.sh wrapper instead of ./b4w.ps1 (leading-'/' regex value under Git Bash).

**Issues Found:** 4

**Most Confusing Aspects:** Shell-wrapper selection on Windows: three launchers (b4w.ps1 / b4w.sh / b4w.bat) and Git Bash's silent '/'-prefix path conversion mean the documented crawl example with -olp "/product/" fails through the wrapper the environment tells you to use, and the caveat lives only in a bundled reference doc, not in --help. Second: a healthy backend being reported as possibly not running by --help --examples.

**Most Valuable Improvements:** 1) Surface the Git Bash leading-'/' caveat (and the ./b4w.sh route) directly in `crawl --help` and the guard's recovery hint. 2) Make --help --examples honest: distinguish 'backend unreachable' from 'backend advertises no tool specs' (the latter is the normal state, since only optional-plugin cliName specs are served). 3) Fix the `createdAt` field so terminal crawl records keep the task's creation instant. 4) Correct the `--args <json>` placeholder to the raw-LoadOptions reality.

**Usability Rating:** 8/10

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

#### Issue 1: Leading-'/' flag values (e.g. -olp "/product/") cannot run through ./b4w.ps1 from Git Bash — the CLI's own crawl example fails on the platform's default shell

From Git Bash in the repo root, run the exact command from `crawl --help` Examples:
./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"

#### Issue 2: `--help --examples` always prints "is the backend running?" on a healthy backend (that endpoint is normally empty)

./b4w.ps1 status                          # Server health: UP
./b4w.ps1 crawl --help --examples
curl -s http://127.0.0.1:8283/mcp/tools/specs   # -> { "tools" : [ ] } in ~3 ms

#### Issue 3: `-a, --args` documented as `<json>` but parsed as a raw LoadOptions string

./b4w.ps1 crawl --help   # option line: '-a, --args <json>  Additional LoadOptions passthrough...'  — while the Notes block on the same page says: 'passes additional LoadOptions through as a raw string (e.g. -a "-nMaxRetry 5")'; references/crawl.md shows -a "-requireSize 100000 -scrollCount 5"

#### Issue 4: crawl status records: `createdAt` silently becomes the finish time on terminal tasks (and is epoch-millis while sibling fields are ISO-8601)

./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 3 --refresh -ol "a.product" --background
./b4w.ps1 crawl status <id>    # while Processing:  createdAt=1791900944936 == startedTime 14:15:44.936
./b4w.ps1 crawl cancel <id>
./b4w.ps1 crawl status <id>    # terminal:         createdAt=1791900988787 == finishTime 14:16:28.782 (43 s later)

