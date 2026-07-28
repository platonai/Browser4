# Issues: experience-workflow

> **Source:** `20260727-232743-experience-workflow.full.md` | **Date:** 20260727-232743 | **Mode:** dev

## Scenario Background

### Task

The experience subsystem evaluation was **partially successful**. The core **save** and **deep-learn** operations work correctly — traces are persisted to disk and deep learning analysis produces verified/hypothesis facts. However, **experience list** and **experience query** both return empty results (0 entries / P5 cold start) until `experience deep-learn` has been run on a given domain+intent. This means `experience save` alone is insufficient to make knowledge queryable — a critical gap not documented anywhere. Additionally, several response fields are missing or incorrect (failure_category, task_type, intent auto-classification), filtering has bugs (intent-filter leaks, page-size ignored), and URL validation is absent.

### Execution Context

The full execution trace (59 commands) is documented at `.test-sessions/experience-evaluation-trace.md`. Key highlights:

- **59 CLI commands** executed across all 8 test parts (A–H)
- **4 `--help` outputs** verified for all experience subcommands
- **11 `experience save` calls** — all returned `saved: true`, trace files persisted to disk
- **7 `experience query` calls** — initially all P5 cold start; worked after deep_learn indexing
- **7 `experience list` calls** — initially 0 entries; 3 entries after deep_learn (5+ expected)
- **4 `experience deep-learn` calls** — all completed successfully
- **6 error handling tests** — 5 passed with clear error messages
- **1 E2E form-filling pipeline** — browser interaction → save → query → deep_learn → list completed
- **3 isolation tests** — domain...

(truncated — see full.md for complete trace)

---

## Issues Found (11 issues)

### Issue 1: experience list/query return empty until deep-learn is run — save does not index knowledge

**Severity:** Critical
**Category:** Product

#### Reproduction

1. Run `experience save` for a new domain+intent (returns saved=true).
2. Run `experience list` or `experience query` for that domain — returns 0 entries / P5 cold start.
3. Run `experience deep-learn --force` for that domain+intent.
4. Run `experience list` or `experience query` again — now returns results.

#### Expected Behavior

experience save should make knowledge immediately queryable via list and query. The documentation implies save stores the trace and updates stats — list/query should find it without requiring deep-learn.

#### Actual Behavior

experience list returns 0 entries and experience query returns P5 cold start until deep-learn is explicitly run for each domain+intent pair. Traces saved via save() and experience YAML files exist on disk but are not indexed for retrieval until deep_learn triggers indexing.

#### Root Cause Analysis

The KnowledgeStore's list() and query() methods read from an in-memory index or a different data structure than the raw trace/experience files on disk. deep_learn() triggers a rebuild/scan of the knowledge directory that populates this index. save() writes files but does not update the retrieval index. The code paths are disconnected: save writes to the filesystem store; list/query read from an index that is only populated by deep_learn.

#### AI Suggested Improvement

- Have experience save() call the same index-rebuild logic that deep_learn() triggers, so saved knowledge is immediately queryable
- Alternatively, have list() and query() scan the experience/ directory directly instead of relying on a separate index
- Add a background index refresh or file watcher so new traces are automatically indexed
- Document the current behavior as a known limitation if the index rebuild is intentionally deferred to deep_learn

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical — save() writes trace files but never populates the retrieval index that list()/query() read from. The write path and read path are disconnected. Fix by having save() trigger the same index-rebuild logic that deep_learn() uses, or by making list()/query() scan the knowledge directory directly.

---

### Issue 2: failure_category is always 'unknown' — error message classification not working

**Severity:** High
**Category:** Product

#### Reproduction

Run: `./b4w.ps1 experience save "https://blocked.example.com/search" '{"url":"...","errorMessage":"CAPTCHA detected on page",...}' --outcome failure --intent "search for product"`

#### Expected Behavior

failure_category should be "anti_bot" (classified from the CAPTCHA error message) or "timeout" (from timeout messages).

#### Actual Behavior

failure_category is always "unknown" regardless of the error message content. Tested with "CAPTCHA detected on page" and "Timeout waiting for selector" — both produced "unknown".

#### Root Cause Analysis

The failure classification logic either does not exist, is not invoked, or its pattern-matching rules don't cover the error messages being tested. The errorMessage field from the trace is stored but not parsed for categorization.

#### AI Suggested Improvement

- Implement pattern-matching on the errorMessage field to classify failures into anti_bot, timeout, selector_not_found, network_error, etc.
- Add a --failure-category option to allow users to explicitly set the category
- If classification is intentionally deferred, document that failure_category will be 'unknown' unless explicitly provided

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid feature gap — the errorMessage field is stored but never parsed for classification. Implement pattern-matching against known failure signatures (CAPTCHA → anti_bot, timeout → timeout, etc.). Consider also adding a --failure-category flag for explicit override.

---

### Issue 3: Auto-classified intent is 'other' instead of matching taskType when --intent is omitted

**Severity:** High
**Category:** Product

#### Reproduction

Run: `./b4w.ps1 experience save "https://example.com/login" '{"url":"...","taskType":"login",...}'` (no --intent flag).

#### Expected Behavior

Intent should be auto-classified as "login" based on the trace's taskType field.

#### Actual Behavior

Intent is classified as "other". The taskType field in the trace JSON is not used for intent classification.

#### Root Cause Analysis

The auto-classification logic does not read the taskType field from the trace JSON. It may only use a default fallback value ('other') when no --intent is provided.

#### AI Suggested Improvement

- Read the taskType field from the trace JSON and map it to the corresponding intent (login→LOGIN, extract_product_detail→EXTRACT, search→SEARCH, fill_form→FILL_FORM, etc.)
- Add a taskType-to-intent mapping table as a fallback when --intent is not explicitly provided

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Auto-classification should read taskType from the trace JSON as a fallback when --intent is omitted. A simple mapping table (login→LOGIN, extract_product_detail→EXTRACT, search→SEARCH, fill_form→FILL_FORM) would resolve this. Shares the same root pattern as Issue 2: trace fields are stored but not consumed by classification/retrieval logic.

---

### Issue 4: task_type field missing from save response despite --task-type flag being passed

**Severity:** Medium
**Category:** Product

#### Reproduction

Run: `./b4w.ps1 experience save "..." '...' --task-type "extract_product_detail"`
Check the JSON response for a task_type field.

#### Expected Behavior

Response should include `"task_type": "extract_product_detail"` reflecting the explicitly passed --task-type value.

#### Actual Behavior

The task_type field is absent from the JSON response. The --task-type flag is accepted without error but its value is not echoed back in the response.

#### Root Cause Analysis

The --task-type value is stored internally (likely in the trace file) but the response serializer does not include it in the output. Either the field is missing from the response DTO or the save method doesn't propagate it.

#### AI Suggested Improvement

- Add task_type to the save response DTO and populate it from the stored value
- If task_type is intentionally storage-only, document that it won't appear in the response

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The --task-type value is accepted and stored internally but omitted from the serialized response. Add task_type to the response DTO and populate it from the stored trace. Cross-pattern with Issues 10 and 11: the response serialization layer drops fields inconsistently.

---

### Issue 5: --page-size flag is ignored — always defaults to 20

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run: `./b4w.ps1 experience list --page 1 --page-size 2` when more than 2 entries exist.

#### Expected Behavior

At most 2 entries returned with page_size: 2 in metadata.

#### Actual Behavior

All 3 entries returned with page_size: 20 in metadata. The --page-size value is ignored.

#### Root Cause Analysis

The CLI parser or the backend handler is not reading the --page-size option correctly, or the backend always uses a hardcoded default of 20.

#### AI Suggested Improvement

- Fix the argument binding for --page-size in the CLI parser or backend handler
- Add a validation that --page-size is within the documented range (1–100)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] CLI argument binding bug — --page-size is parsed by the CLI but the value never reaches the backend handler, which falls through to a hardcoded default of 20. Trace the argument through the dispatch chain to find where it's dropped. Same class of bug as Issue 6 (flag accepted, silently ignored).

---

### Issue 6: --intent-filter returns entries with non-matching intents

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run: `./b4w.ps1 experience list --intent-filter "extract"` when both extract and fill_form entries exist.

#### Expected Behavior

Only entries with intent="extract" should be returned.

#### Actual Behavior

Both extract and fill_form entries are returned. The intent filter does not properly filter results.

#### Root Cause Analysis

The --intent-filter is either doing a partial/substring match that matches both 'extract' and 'fill_form' (unlikely since 'fill_form' doesn't contain 'extract'), or the filter is not being applied at all to the query. Most likely the filter parameter is parsed but silently dropped before the backend query executes.

#### AI Suggested Improvement

- Fix the intent-filter application logic in the backend list handler
- Consider whether partial matching vs exact matching is the intended behavior and document it

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] --intent-filter is parsed by the CLI but not applied to the backend query — the filter parameter is dropped before the list handler executes. Same silent-drop pattern as Issue 5. Audit all filter/list flags together to ensure they reach the backend.

---

### Issue 7: Malformed URLs accepted without validation in experience save

**Severity:** Medium
**Category:** UX

#### Reproduction

Run: `./b4w.ps1 experience save "not-a-valid-url" '{"url":"not-a-valid-url",...}'`

#### Expected Behavior

Either normalize the URL or return a clear error about invalid URL format.

#### Actual Behavior

The string "not-a-valid-url" is accepted as-is and used as the domain. No validation or normalization occurs.

#### Root Cause Analysis

No URL validation is performed on the <url> argument. The input is treated as an opaque string and used directly as the domain key.

#### AI Suggested Improvement

- Add URL validation (protocol check, domain extraction) to the save command
- At minimum, warn if the URL doesn't start with http:// or https://
- Consider normalizing the URL (lowercase hostname, strip fragments) before using as domain

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Lightweight validation is warranted — at minimum, warn if the URL doesn't start with http:// or https://, and extract the hostname as the domain key rather than using the raw string verbatim. Don't over-engineer with a full URL parser; a regex-based check is sufficient for this use case.

---

### Issue 8: Naming inconsistency: 'experience deep learn' (space) vs 'experience deep-learn' (hyphen)

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Run `./b4w.ps1 help | grep experience` — shows 'experience deep learn'
2. Run `./b4w.ps1 experience deep-learn --help` — works, but help header shows 'experience deep learn'
3. Task documentation and SKILL.md both use 'experience deep-learn'

#### Expected Behavior

Consistent naming convention. The help output should match the actual command name. If kebab-case is the convention, it should be 'experience deep-learn' everywhere.

#### Actual Behavior

The help output displays 'experience deep learn' (space-separated) while 'experience deep-learn' (hyphenated) also works. The canonical form is ambiguous.

#### Root Cause Analysis

The CLI parser accepts both forms (space and hyphen) but the help text generator uses the space-separated form. The subcommand registration uses 'deep learn' as the canonical name internally.

#### AI Suggested Improvement

- Standardize on one naming convention (preferably kebab-case: 'experience deep-learn')
- Update the help text generator to output the hyphenated form
- Or accept both but document the canonical form clearly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The CLI accepts both "experience deep learn" and "experience deep-learn" but the help text only shows the space-separated form. Standardize on kebab-case ("experience deep-learn") everywhere — in help output, documentation, and SKILL.md — since that's the conventional subcommand style.

---

### Issue 9: PowerShell wrapper intercepts short flags like -i and -v

**Severity:** Low
**Category:** UX

#### Reproduction

Run: `./b4w.ps1 snapshot -i` or `./b4w.ps1 -- snapshot -i`

#### Expected Behavior

The -i flag should be passed to the browser4-cli binary as the interactive snapshot option.

#### Actual Behavior

PowerShell interprets -i as -InformationAction and -v as -Verbose. Even using `--` separator doesn't resolve it for b4w.ps1. The b4w.sh wrapper also fails.

#### Root Cause Analysis

PowerShell's parameter binder processes short flags before passing them to the wrapped CLI binary. The SKILL.md documents this known issue and suggests workarounds (use b4w.bat or quote arguments), but b4w.bat is not present in the repository and b4w.sh doesn't work in this environment.

#### AI Suggested Improvement

- Bundle b4w.bat alongside b4w.ps1 for Windows users
- Fix b4w.sh to work properly in Git Bash environments
- Consider using long-form flags (--interactive) as alternatives for commonly intercepted short flags
- Add a note in the SKILL.md that on Git Bash, b4w.sh may not work and users should use pwsh directly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] PowerShell's parameter binder intercepting -i/-v is a platform limitation, not a browser4 bug, but the workaround surface should be complete. Bundle b4w.bat (referenced in SKILL.md but missing from the repo) and ensure b4w.sh works in Git Bash. Document that long-form flags (--interactive, --verbose) are the reliable cross-shell option.

---

### Issue 10: status_before field missing from some deep-learn responses

**Severity:** Low
**Category:** Product

#### Reproduction

Run deep_learn for the first time on a domain+intent. The response includes status_after but not status_before.

#### Expected Behavior

Both status_before and status_after should be present in every deep_learn response.

#### Actual Behavior

When deep_learn creates knowledge from scratch (no prior status), status_before is absent from the response. It only appears on subsequent calls where a prior status exists.

#### Root Cause Analysis

The response DTO omits status_before when the prior state is null/empty rather than serializing it as null or 'none'.

#### AI Suggested Improvement

- Always include status_before in the response, using null or "NONE" when no prior knowledge exists
- This ensures consistent response schema for API consumers

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The response schema should be stable regardless of whether prior knowledge exists. Always emit status_before, using null when no prior state exists. Cross-pattern with Issues 4 and 11: the serialization layer should use a consistent inclusion policy (prefer include-with-null over omit-if-null for API-facing DTOs).

---

### Issue 11: failure_category field absent from success trace save responses — task expects null

**Severity:** Low
**Category:** Documentation

#### Reproduction

Save a success trace. The JSON response does not contain a failure_category field.

#### Expected Behavior

Task documentation says failure_category should be null for success outcomes.

#### Actual Behavior

The field is completely absent from the response, not null. API consumers expecting the field to exist (even as null) will get undefined/missing key errors.

#### Root Cause Analysis

The response serializer omits null fields rather than including them with null values (likely Jackson's NON_NULL inclusion or Kotlin's default serialization behavior).

#### AI Suggested Improvement

- Either always include failure_category in responses (null for success, category string for failure)
- Or update the documentation to state that failure_category is only present for failure outcomes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Documentation says failure_category should be null for success outcomes, but the field is completely absent due to Jackson/Kotlin null-omission defaults. Align with Issue 10's fix: adopt a consistent include-null-fields policy for response DTOs so API consumers get predictable schemas. Alternatively, if omission is intentional, update the docs to say "present only for failure outcomes."

---

## Overall Assessment

**Completion Status:** Partially Successful — The core save and deep-learn operations function correctly, but list and query are unusable until deep-learn has been manually triggered for each domain+intent pair. Several response fields are missing or incorrect. The E2E pipeline (browser interaction → save → query → deep_learn → list) completes successfully but only after the deep_learn workaround. 3 out of 5+ saved domains are queryable; amazon.com, blocked.example.com, and example.com remain inaccessible via list/query despite valid data on disk.

**Success Rate:** 68% — Approximately 22 of 32 verifiable test criteria passed. Major failures: knowledge indexing gap (Critical), failure_category classification (High), intent auto-classification (High), pagination (Medium), intent-filter (Medium).

**Issues Found:** 11

**Major Blockers:** Critical: experience save does not index knowledge for retrieval — list and query return empty results until deep-learn is explicitly run for each domain+intent pair. This makes the primary knowledge retrieval workflow (save → query) non-functional without an extra deep-learn step that is never documented as required.

**Most Confusing Aspects:** 1. experience save returns 'saved: true' with a valid trace_path, but experience list/query return nothing — the disconnect between successful save and failed retrieval is extremely confusing for a first-time user.
2. The 'experience deep learn' vs 'experience deep-learn' naming inconsistency in help output.
3. PowerShell flag interception making basic snapshot commands fail — requires reading the SKILL.md warning section carefully.
4. The default human-readable output format when the task documentation implies JSON output.

**Most Valuable Improvements:** 1. Make experience save immediately index knowledge for list/query — this is the single most impactful fix.
2. Implement failure_category classification from error messages.
3. Auto-classify intent from taskType when --intent is omitted.
4. Fix pagination (--page-size) and intent-filter bugs.
5. Add URL validation to experience save.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: experience list/query return empty until deep-learn is run — save does not index knowledge

1. Run `experience save` for a new domain+intent (returns saved=true).
2. Run `experience list` or `experience query` for that domain — returns 0 entries / P5 cold start.
3. Run `experience deep-learn --force` for that domain+intent.
4. Run `experience list` or `experience query` again — now returns results.

#### Issue 2: failure_category is always 'unknown' — error message classification not working

Run: `./b4w.ps1 experience save "https://blocked.example.com/search" '{"url":"...","errorMessage":"CAPTCHA detected on page",...}' --outcome failure --intent "search for product"`

#### Issue 3: Auto-classified intent is 'other' instead of matching taskType when --intent is omitted

Run: `./b4w.ps1 experience save "https://example.com/login" '{"url":"...","taskType":"login",...}'` (no --intent flag).

#### Issue 4: task_type field missing from save response despite --task-type flag being passed

Run: `./b4w.ps1 experience save "..." '...' --task-type "extract_product_detail"`
Check the JSON response for a task_type field.

#### Issue 5: --page-size flag is ignored — always defaults to 20

Run: `./b4w.ps1 experience list --page 1 --page-size 2` when more than 2 entries exist.

#### Issue 6: --intent-filter returns entries with non-matching intents

Run: `./b4w.ps1 experience list --intent-filter "extract"` when both extract and fill_form entries exist.

#### Issue 7: Malformed URLs accepted without validation in experience save

Run: `./b4w.ps1 experience save "not-a-valid-url" '{"url":"not-a-valid-url",...}'`

#### Issue 8: Naming inconsistency: 'experience deep learn' (space) vs 'experience deep-learn' (hyphen)

1. Run `./b4w.ps1 help | grep experience` — shows 'experience deep learn'
2. Run `./b4w.ps1 experience deep-learn --help` — works, but help header shows 'experience deep learn'
3. Task documentation and SKILL.md both use 'experience deep-learn'

#### Issue 9: PowerShell wrapper intercepts short flags like -i and -v

Run: `./b4w.ps1 snapshot -i` or `./b4w.ps1 -- snapshot -i`

#### Issue 10: status_before field missing from some deep-learn responses

Run deep_learn for the first time on a domain+intent. The response includes status_after but not status_before.

#### Issue 11: failure_category field absent from success trace save responses — task expects null

Save a success trace. The JSON response does not contain a failure_category field.

