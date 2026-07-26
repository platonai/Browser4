#!/usr/bin/env pwsh
<#
.SYNOPSIS
Experience workflow: exercise the full experience system lifecycle (Progressive
Experience Memory v2) and verify correctness.

.DESCRIPTION
Exercises all four experience MCP tools across the Fast Learning → Query →
List → Deep Learning pipeline:

  experience_save      — Fast Learning: save execution trace + update stats
  experience_query     — Intent-based knowledge retrieval (6-level fallback)
  experience_list      — List stored knowledge by domain/intent/status
  experience_deep_learn — Deep Learning: analyze, build facts, promote

The experience tools are MCP tools registered in the backend, NOT CLI
commands.  The agent calls them via HTTP POST to the backend's /mcp/call-tool
endpoint while using standard CLI commands for browser interaction.

Uses an AI agent (Claude/Kimi) to check the result of each step and report
any issues found against browser4-cli usability and reliability.

.NOTES
Run from the repo root:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/experience.ps1

In production mode:
  pwsh ./browser4-tests/real-world-scenarios/tasks/workflow/experience.ps1 -Production
#>

[CmdletBinding()]
param(
    [switch] $Silent,

    # Run in production mode (browser4-cli instead of ./b4w.ps1).
    [switch] $Production,

    # Maximum minutes to wait for the AI agent to complete.
    [int] $TimeoutMinutes = 0
)

$ErrorActionPreference = 'Stop'

# -- Set mode before loading common.ps1 ------------------------------------------
if ($Production -and -not $browser4cliMode -and -not $env:BROWSER4CLI_MODE) {
    $browser4cliMode = 'production'
}

# -- Resolve the scripts directory -----------------------------------------------
# $PSScriptRoot = .../real-world-scenarios/tasks/workflow
# Go up two levels to real-world-scenarios/, then into scripts/
$ScenariosRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot '..' '..')
)
$ScriptsDir = Join-Path $ScenariosRoot 'scripts'

if (-not (Test-Path -LiteralPath $ScriptsDir -PathType Container)) {
    Write-Host "ERROR: Cannot find scripts directory at: $ScriptsDir" -ForegroundColor Red
    Write-Host "  Expected: browser4-tests/real-world-scenarios/scripts/" -ForegroundColor DarkGray
    exit 1
}

# -- Dot-source the shared helpers -----------------------------------------------
. "$ScriptsDir/common.ps1"

# ===============================================================================
# Task-specific prompt (built from single-quoted fragments to avoid PowerShell
# escape-character collisions with Markdown backtick-quoted inline code).
# ===============================================================================

# Build the CLI reference string once so it is consistent with $generalPrompt.
$cliRef = $cliInvocation

# Single-quoted fragments avoid backtick-interpretation issues.
$taskBody = @'

Execute the following experience system test sequence **in order**. After each
command, inspect its output and verify correctness before proceeding to the next
step.  Report any issues (unexpected output, missing data, confusing messages,
errors, wrong confidence scores, wrong tier levels, incorrect intent
classification, serialization errors) for each step individually.

## Background: The Browser4 Experience System

Browser4 has a **Progressive Experience Memory (PEM v2)** system that learns
from task execution traces.  It exposes four CLI commands:

| CLI Command             | Purpose                                      | Speed        |
|-------------------------|----------------------------------------------|--------------|
| `experience save`       | Fast Learning: save trace + update stats     | ~tens of ms  |
| `experience query`      | Intent-based knowledge retrieval             | ~single-digit ms |
| `experience list`       | List stored knowledge by domain/intent       | ~ms          |
| `experience deep-learn` | Deep Learning: analyze, build facts, promote | ~seconds     |

## How to Call Experience Commands

Every experience command follows the same invocation pattern:

```
__CLI__ experience <action> <required-args> [--options]
```

The CLI communicates with the backend automatically — no port configuration or
MCP envelope unwrapping needed.  The response is returned directly as text
(generally JSON).

### Command Reference

**experience save `<url>` `<trace>`**
Positional args: `url` (the page URL), `trace` (JSON-encoded execution trace).
Options: `--outcome` (default "success"), `--intent` (free-text intent),
`--task-type` (canonical task type).

**experience query `<url>`**
Positional args: `url` (target URL).
Options: `--intent` (free-text intent for classification).

**experience list**
Positional args: none.
Options: `--filter` (domain filter), `--intent-filter` (intent filter),
`--page` (default 1), `--page-size` (default 20, max 100).

**experience deep-learn `<url>` `<intent>`**
Positional args: `url` (target URL), `intent` (free-text intent).
Options: `--force` (boolean flag, bypasses sampling check).

### The trace argument

The `trace` argument for `experience save` is a JSON object serialized as a
string.  For example:

```json
{
  "url": "https://example.com/page",
  "taskType": "extract_product_detail",
  "outcome": "success",
  "steps": [
    {"order": 1, "action": "navigate", "value": "https://example.com/page"},
    {"order": 2, "action": "click", "selector": "#btn", "result": "success"}
  ],
  "durationMs": 2500
}
```

When passing this on the command line, wrap the JSON in single quotes so the
shell preserves the inner double quotes:

```
__CLI__ experience save "https://example.com/page" '{"url":"https://example.com/page","taskType":"extract_product_detail","outcome":"success","steps":[{"order":1,"action":"navigate","value":"https://example.com/page"}],"durationMs":2500}' --intent "extract data"
```

If the JSON contains single quotes, write it to a temp file and use shell
substitution instead:

```
echo '...' > /tmp/trace.json
__CLI__ experience save "https://example.com/page" "$(cat /tmp/trace.json)" --intent "extract data"
```

Valid task types: `navigate`, `search`, `extract_product_list`,
`extract_product_detail`, `extract_article`, `add_to_cart`, `checkout`,
`fill_form`, `login`, `extract_table`, `download_file`, `monitor_change`.

Valid intents (free-text, auto-classified): BUY, SEARCH, BOOK, LOGIN, CHECKOUT,
EXTRACT, COMPARE, DOWNLOAD, READ, FILL_FORM, MONITOR, OTHER.

The knowledge retrieval uses a **6-level fallback chain**:
P1 (domain,intent) → P2 (domain,url_pattern) → P3 (family,intent) →
P4 (category,intent) → P5 (universal,intent) → P6 (cold start).

The verification pipeline is: HYPOTHESIS → CANDIDATE → VERIFIED
(CONTESTED triggers re-verification).

## Pre-requisites

Before starting the sequence, verify the experience commands are available:

```
__CLI__ help | grep experience
```

Expected: four commands listed: `experience deep-learn`, `experience list`,
`experience query`, `experience save`.  If none appear, record a **Critical
Reliability** issue — the experience CLI subcommands are not registered.

---

# Part A — Command Discovery & Help

## A.1 — List experience commands

Run:
```
__CLI__ help | grep experience
```

Verify:
- Exactly 4 commands appear: `experience deep-learn`, `experience list`,
  `experience query`, `experience save`.
- The names use kebab-case, matching the `__CLI__ <command>` convention.

## A.2 — Help for each command

Run each command with `--help` and verify it prints usage:

```
__CLI__ experience save --help
```

Verify:
- Usage line shows: `experience save <url> <trace>`.
- Options: `--outcome`, `--intent`, `--task-type` are documented.
- The help text describes what the command does.

Repeat for:
```
__CLI__ experience query --help
__CLI__ experience list --help
__CLI__ experience deep-learn --help
```

Verify each prints usage with its expected positional args and options.

---

# Part B — experience save (Fast Learning)

## B.1 — Save a success trace

First, use browser4-cli to open a session and navigate to a test page:

```
__CLI__ kill-all
__CLI__ open
__CLI__ goto https://httpbin.org/get
__CLI__ snapshot -i
```

Verify:
- All commands exit with code 0.
- The page is `https://httpbin.org/get`.
- The snapshot contains the httpbin JSON response.

Now save the execution trace:

```
__CLI__ experience save "https://httpbin.org/get" '{"url":"https://httpbin.org/get","taskType":"extract_article","outcome":"success","steps":[{"order":1,"action":"navigate","value":"https://httpbin.org/get"},{"order":2,"action":"snapshot","result":"success"}],"durationMs":1500,"finalPageUrl":"https://httpbin.org/get","finalPageTitle":"httpbin.org/get"}' --intent "extract data from this page"
```

Verify:
- The command exits with code 0.
- The output is valid JSON.
- `"saved"` is `true`.
- `"domain"` is `"httpbin.org"`.
- `"intent"` is `"extract"` (the free-text "extract data from this page"
  is classified as EXTRACT intent).
- `"outcome"` is `"success"`.
- `"trace_path"` is a non-empty string.
- `"confidence"` is a number > 0.0.
- `"retrieval_tier"` is present.
- `"failure_category"` is `null` (success outcome).

## B.2 — Save a second trace (same domain, same intent)

Navigate to a different page on the same domain:

```
__CLI__ goto https://httpbin.org/links/10
```

Then save:

```
__CLI__ experience save "https://httpbin.org/links/10" '{"url":"https://httpbin.org/links/10","taskType":"extract_article","outcome":"success","steps":[{"order":1,"action":"navigate","value":"https://httpbin.org/links/10"}],"durationMs":1200}' --intent "extract data"
```

Verify:
- `"saved"` is `true`.
- `"domain"` is `"httpbin.org"`.
- `"confidence"` has increased (or stayed at maximum) relative to B.1.

## B.3 — Save a failure trace

Construct a trace representing a failed extraction (e.g., CAPTCHA):

```
__CLI__ experience save "https://blocked.example.com/search" '{"url":"https://blocked.example.com/search","taskType":"search","outcome":"failure","errorMessage":"CAPTCHA detected on page","steps":[{"order":1,"action":"click","selector":"#search-btn","result":"error: captcha"}],"durationMs":800}' --outcome failure --intent "search for product"
```

Verify:
- `"saved"` is `true`.
- `"outcome"` is `"failure"`.
- `"failure_category"` is NOT null — it should be `"anti_bot"` (classified
  from the CAPTCHA error message).
- `"domain"` is `"blocked.example.com"` (correctly extracted from URL).
- The response does NOT crash on failure traces.

## B.4 — Save without explicit intent (auto-classification)

Save a trace with no `--intent` flag — the system should classify from the
trace's `taskType`:

```
__CLI__ experience save "https://example.com/login" '{"url":"https://example.com/login","taskType":"login","outcome":"success","steps":[{"order":1,"action":"type","selector":"#username","value":"user"},{"order":2,"action":"type","selector":"#password","value":"pass"},{"order":3,"action":"click","selector":"#login-btn","result":"success"}],"durationMs":3200}'
```

Verify:
- `"saved"` is `true`.
- `"intent"` is classified (should be `"login"` or similar based on taskType).
- The system does NOT require an explicit `--intent` argument.

## B.5 — Save with explicit task_type

```
__CLI__ experience save "https://amazon.com/dp/test-product" '{"url":"https://amazon.com/dp/test-product","taskType":"extract_product_detail","outcome":"success","steps":[{"order":1,"action":"navigate","value":"https://amazon.com/dp/test-product"}],"durationMs":1000}' --intent "buy this product" --task-type "extract_product_detail"
```

Verify:
- `"saved"` is `true`.
- `"domain"` is `"amazon.com"`.
- `"task_type"` is `"extract_product_detail"`.
- `"intent"` is `"buy"` (the free-text "buy this product" maps to BUY).

---

# Part C — experience query (Intent-Based Resolution)

## C.1 — Cold start query (no prior knowledge)

Query a domain that has never been seen before:

```
__CLI__ experience query "https://never-seen-before.com/some/page" --intent "extract data"
```

Verify:
- The command exits with code 0.
- `"tier"` is `"P5"` (cold start) — no knowledge exists for this domain.
- `"intent"` is classified (e.g., `"extract"`).
- `"confidence"` is 0.0 (or very low).
- The response is NOT an error — cold start is a valid state.

## C.2 — Query after save (should find knowledge)

Now query a domain where traces were saved in Part B:

```
__CLI__ experience query "https://httpbin.org/get" --intent "extract data from page"
```

Verify:
- `"tier"` is NOT `"P5"` — knowledge should be found.
- `"domain"` is `"httpbin.org"`.
- `"confidence"` is > 0.0 (should reflect the 2 success traces saved).
- `"intent"` is `"extract"`.

## C.3 — Query without intent (URL-only)

```
__CLI__ experience query "https://httpbin.org/get"
```

Verify:
- The query succeeds (no error).
- Some result is returned even without an explicit intent.
- The behavior is documented (what happens when intent is omitted?).

## C.4 — Query with different intent than saved

Query the httpbin.org domain with an intent that was NOT saved:

```
__CLI__ experience query "https://httpbin.org/get" --intent "buy this product now"
```

Verify:
- The query succeeds.
- The tier may fall back to a wider scope (family/category/universal) since
  no BUY intent traces exist for httpbin.org.
- The fallback behavior is documented (which tier was returned?).

---

# Part D — experience list

## D.1 — List all entries

```
__CLI__ experience list
```

Verify:
- The command exits with code 0.
- The response contains at least the entries saved in Part B.
- `"total"` is a non-zero integer (at least 5 — the 5 traces from B.1–B.5).
- Each entry has: `domain`, `intent`, `status`, `confidence`, `sample_count`.
- Entries are organized by `(domain, intent)`.

## D.2 — List filtered by domain

```
__CLI__ experience list --filter "httpbin.org"
```

Verify:
- Only httpbin.org entries appear.
- `"total"` reflects only the filtered count.
- amazon.com, blocked.example.com, and example.com entries are NOT present.

## D.3 — List filtered by intent

```
__CLI__ experience list --intent-filter "extract"
```

Verify:
- Only entries with intent "extract" appear (httpbin.org ones).
- BUY, LOGIN, SEARCH entries are NOT present.

## D.4 — List with pagination

```
__CLI__ experience list --page 1 --page-size 2
```

Verify:
- At most 2 entries are returned.
- Pagination metadata is present (page, page_size, total).
- Run page 2 — different entries appear (no overlap with page 1).

---

# Part E — experience deep-learn (Deep Learning)

## E.1 — Save enough traces to build confidence

We need multiple successful traces for a domain+intent to make deep_learn
meaningful.  Save 3 more traces for `httpbin.org` + `extract`:

```
__CLI__ experience save "https://httpbin.org/ip" '{"url":"https://httpbin.org/ip","taskType":"extract_article","outcome":"success","steps":[{"order":1,"action":"navigate","value":"https://httpbin.org/ip"}],"durationMs":800}' --intent "extract data"
```

Repeat for `https://httpbin.org/headers` and `https://httpbin.org/user-agent`.

## E.2 — Run deep_learn (first time — should create hypothesis)

```
__CLI__ experience deep-learn "https://httpbin.org/get" "extract data" --force
```

Verify:
- The command exits with code 0.
- `"completed"` is `true`.
- `"domain"` is `"httpbin.org"`.
- `"intent"` is `"extract"`.
- `"status_before"` and `"status_after"` are present — at least one should
  be `"hypothesis"`.
- `"new_confidence"` is present (a number representing current confidence).
- `"selectors_found"` is a non-negative integer.

## E.3 — Run deep_learn again (should skip or complete)

```
__CLI__ experience deep-learn "https://httpbin.org/get" "extract data"
```

Verify:
- If confidence >= 0.90: `"completed"` is `false` and `"message"` contains
  "Skipped" and "0.9".  This is correct — high-confidence knowledge does not
  need re-learning.
- If confidence < 0.90: `"completed"` is `true`.
- The response is NOT an error in either case.

## E.4 — Force deep_learn (bypass sampling check)

```
__CLI__ experience deep-learn "https://httpbin.org/get" "extract data" --force
```

Verify:
- `"completed"` is `true` (force=true always proceeds).
- `"promoted"` may be `true` or `false` depending on confidence.
- The system does NOT skip when `--force` is set.

## E.5 — Run deep_learn on a domain with only failure traces

Save 3 failure traces for a domain, then run deep_learn:

```
__CLI__ experience save "https://flaky-site.com/page" '{"url":"https://flaky-site.com/page","taskType":"extract_article","outcome":"failure","errorMessage":"Timeout waiting for selector","steps":[{"order":1,"action":"navigate","value":"https://flaky-site.com/page"},{"order":2,"action":"click","selector":"#missing","result":"error: timeout"}],"durationMs":5000}' --outcome failure --intent "extract data"
```

Repeat 2 more times (same domain+intent), then:

```
__CLI__ experience deep-learn "https://flaky-site.com/page" "extract data" --force
```

Verify:
- deep_learn handles failure-dominated knowledge gracefully.
- `"completed"` is `true`.
- The response includes meaningful status information even when all traces
  are failures.

---

# Part F — Error Handling

## F.1 — Missing required argument (url)

```
__CLI__ experience save
```

Or, with only the trace:

```
__CLI__ experience save "" '{"url":""}'
```

Verify:
- The command prints a clear error (usage, or "missing required argument").
- It does NOT crash or produce a garbled JSON response.

## F.2 — Invalid trace JSON

```
__CLI__ experience save "https://example.com" "this is not valid json {{{"
```

Verify:
- The response is an error.
- The error message mentions JSON parsing failure (not a generic crash).
- The command does NOT corrupt the knowledge store.

## F.3 — Missing required argument for deep_learn (intent)

```
__CLI__ experience deep-learn "https://example.com"
```

Verify:
- The command prints a clear error about the missing `intent` argument.
- It does NOT crash or hang.

## F.4 — Unknown domain in query (not an error)

```
__CLI__ experience query "https://this-domain-does-not-exist-12345.com/" --intent "search"
```

Verify:
- The command exits with code 0 (NOT an error).
- `"tier"` is `"P5"` (cold start).
- The system gracefully handles unknown domains.

## F.5 — Malformed URL

```
__CLI__ experience save "not-a-valid-url" '{"url":"not-a-valid-url","taskType":"navigate","outcome":"success","steps":[]}' --outcome success
```

Verify:
- The system either normalizes the URL or returns a clear error.
- It does NOT crash or produce a garbled response.

## F.6 — experience query without URL

```
__CLI__ experience query
```

Verify:
- The command prints a clear usage error (URL is required).
- It does NOT crash or produce confusing output.

---

# Part G — End-to-End Browser + Experience Pipeline

This section tests the full loop: browser interaction → save trace → query
knowledge → deep_learn → list verification.

## G.1 — E2E: form filling trace

Open a browser session and fill in a form on httpbin:

```
__CLI__ kill-all
__CLI__ open
__CLI__ goto https://httpbin.org/forms/post
__CLI__ snapshot -i
```

Identify form fields from the snapshot, then fill the form using browser4-cli
and submit.  Record every CLI command you used and whether it succeeded.

After submission, navigate to the result page and capture a snapshot.

Construct a trace JSON describing the full form-filling session and save it:

```
__CLI__ experience save "https://httpbin.org/forms/post" '<your-constructed-trace-json>' --intent "fill out a form" --task-type "fill_form"
```

Verify:
- `"saved"` is `true`.
- `"intent"` is `"fill_form"`.
- `"domain"` is `"httpbin.org"`.

## G.2 — E2E: Query after form filling

```
__CLI__ experience query "https://httpbin.org/forms/post" --intent "fill out a form"
```

Verify:
- Knowledge from G.1 is retrievable.
- `"intent"` is `"fill_form"`.

## G.3 — E2E: Deep learn after form filling

```
__CLI__ experience deep-learn "https://httpbin.org/forms/post" "fill out a form" --force
```

Verify:
- `"completed"` is `true`.
- The `fill_form` intent now has deep-learned knowledge.

## G.4 — E2E: Final list verification

```
__CLI__ experience list
```

Verify:
- All saved entries across all parts are present.
- The `fill_form` entry from G.1 is listed.
- No entries are missing, duplicated, or corrupted.
- Compare the `total` count against expected (at least 11 entries from B–G).

---

# Part H — Cross-Intent & Cross-Domain Isolation

## H.1 — Verify domain isolation

Query for one domain's knowledge and confirm it does NOT leak
knowledge from other domains:

```
__CLI__ experience query "https://httpbin.org/get" --intent "extract data"
```

Verify:
- The result is scoped to `httpbin.org`.
- No knowledge from `amazon.com` or `blocked.example.com` leaks in.

## H.2 — Verify intent isolation

Query for `httpbin.org` with the `fill_form` intent and verify it does NOT
return knowledge from the `extract` intent:

```
__CLI__ experience query "https://httpbin.org/forms/post" --intent "fill out a form"
```

Verify:
- The query resolves correctly.
- The retrieved knowledge is specifically for `fill_form`, not a mix of
  `extract` and `fill_form`.

## H.3 — Verify list filter isolation

```
__CLI__ experience list --filter "httpbin.org"
```

Verify:
- Only httpbin.org domains appear — no cross-domain contamination.

---

## Success Criteria

### Fast Learning (experience save)
1. Success traces save correctly with `saved: true`, domain extraction,
   intent classification, and confidence > 0.0.
2. Failure traces save correctly with `failure_category` auto-classified
   from the error message.
3. Auto-classification works when `--intent` is omitted (from taskType).
4. Explicit `--task-type` is preserved in the response.

### Intent-Based Query (experience query)
5. Cold start queries return P5 tier with low confidence — no errors.
6. Queries after save return knowledge (tier < P5, confidence > 0.0).
7. URL-only queries (no `--intent`) work correctly.
8. Intent-based fallback works: different intent → wider scope tier.

### List (experience list)
9. Lists all entries with accurate `total` count.
10. Domain (`--filter`) and intent (`--intent-filter`) filters work correctly
    (no cross-contamination).
11. Pagination works (`--page` / `--page-size` respected).

### Deep Learning (experience deep-learn)
12. First deep_learn creates hypothesis-level facts.
13. High-confidence skip works (confidence ≥ 0.90 → `completed: false` with
    clear message).
14. `--force` bypasses the sampling check.
15. Failure-only domains are handled gracefully.

### Error Handling
16. Missing required arguments produce clear, structured errors (not crashes).
17. Invalid trace JSON produces a parsing error (not a crash).
18. Malformed URLs are handled gracefully.
19. Unknown domains return cold start (P5), not errors.
20. Missing positional args print usage, not a crash/stack trace.

### Pipeline Integrity
21. The full pipeline — browser interaction → save → query → list →
    deep_learn → list — works end-to-end without data loss or corruption.
22. Cross-domain isolation: knowledge is scoped per domain.
23. Cross-intent isolation: knowledge is scoped per intent within a domain.
24. No stale, duplicate, or corrupted entries appear in `list`.

### CLI Quality
25. All four experience commands print useful `--help` text.
26. Response JSON uses consistent snake_case field names (`trace_path`,
    `failure_category`, `task_type`, `retrieval_tier`, `status_before`,
    `status_after`, etc.).
27. Exit codes: 0 for success/valid results, non-zero for errors.
28. Commands work correctly through the `__CLI__` wrapper (no MCP envelope,
    no manual port discovery, no curl).

## Notes

- The `trace` argument must be a **JSON string** containing a trace object.
  Wrap it in **single quotes** on the shell so the inner double quotes
  are preserved.  If the JSON contains single quotes, write it to a temp
  file and use `"$(cat /tmp/trace.json)"` instead.
- The `experience save` and `experience deep-learn` commands do NOT require
  a browser session — they operate on the knowledge store directly.  Only
  Part G (E2E) needs an active browser session for form filling.
- The backend auto-starts with the first `__CLI__` command.  No manual
  server setup is needed.
- Pay attention to the `retrieval_tier` field in query results — it documents
  which level of the 6-level fallback chain resolved the query.
- The `--force` flag on `experience deep-learn` is a boolean flag — just
  include it, no `=true` needed.
- Record any confusing output, misleading messages, or discoverability issues
  as you encounter them.

'@

# Substitute the CLI invocation reference into the single-quoted prompt body.
$taskPrompt = $taskBody.Replace('__CLI__', $cliRef)

# -- Build the full prompt and invoke the agent ----------------------------------
$prompt = $generalPrompt + $taskPrompt

$invokeParams = @{
    Prompt       = $prompt
    ScenarioName = 'experience-workflow'
}
if ($Silent) {
    $invokeParams['Silent'] = $true
}
if ($TimeoutMinutes -gt 0) {
    $invokeParams['TimeoutSeconds'] = $TimeoutMinutes * 60
}

Invoke-Agent @invokeParams
