Now let me produce the final deliverables.

---

# A. Task Result

The experience subsystem evaluation was **partially successful**. The core **save** and **deep-learn** operations work correctly — traces are persisted to disk and deep learning analysis produces verified/hypothesis facts. However, **experience list** and **experience query** both return empty results (0 entries / P5 cold start) until `experience deep-learn` has been run on a given domain+intent. This means `experience save` alone is insufficient to make knowledge queryable — a critical gap not documented anywhere. Additionally, several response fields are missing or incorrect (failure_category, task_type, intent auto-classification), filtering has bugs (intent-filter leaks, page-size ignored), and URL validation is absent.

# B. Execution Trace

The full execution trace (59 commands) is documented at `.test-sessions/experience-evaluation-trace.md`. Key highlights:

- **59 CLI commands** executed across all 8 test parts (A–H)
- **4 `--help` outputs** verified for all experience subcommands
- **11 `experience save` calls** — all returned `saved: true`, trace files persisted to disk
- **7 `experience query` calls** — initially all P5 cold start; worked after deep_learn indexing
- **7 `experience list` calls** — initially 0 entries; 3 entries after deep_learn (5+ expected)
- **4 `experience deep-learn` calls** — all completed successfully
- **6 error handling tests** — 5 passed with clear error messages
- **1 E2E form-filling pipeline** — browser interaction → save → query → deep_learn → list completed
- **3 isolation tests** — domain and intent isolation correct for indexed entries

# C/D. Issues & Assessment (JSON)

```json
{
  "issues": [
    {
      "title": "experience list/query return empty until deep-learn is run — save does not index knowledge",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "1. Run `experience save` for a new domain+intent (returns saved=true).\n2. Run `experience list` or `experience query` for that domain — returns 0 entries / P5 cold start.\n3. Run `experience deep-learn --force` for that domain+intent.\n4. Run `experience list` or `experience query` again — now returns results.",
      "expected": "experience save should make knowledge immediately queryable via list and query. The documentation implies save stores the trace and updates stats — list/query should find it without requiring deep-learn.",
      "actual": "experience list returns 0 entries and experience query returns P5 cold start until deep-learn is explicitly run for each domain+intent pair. Traces saved via save() and experience YAML files exist on disk but are not indexed for retrieval until deep_learn triggers indexing.",
      "rootCause": "The KnowledgeStore's list() and query() methods read from an in-memory index or a different data structure than the raw trace/experience files on disk. deep_learn() triggers a rebuild/scan of the knowledge directory that populates this index. save() writes files but does not update the retrieval index. The code paths are disconnected: save writes to the filesystem store; list/query read from an index that is only populated by deep_learn.",
      "codePointer": "",
      "suggestion": "- Have experience save() call the same index-rebuild logic that deep_learn() triggers, so saved knowledge is immediately queryable\n- Alternatively, have list() and query() scan the experience/ directory directly instead of relying on a separate index\n- Add a background index refresh or file watcher so new traces are automatically indexed\n- Document the current behavior as a known limitation if the index rebuild is intentionally deferred to deep_learn"
    },
    {
      "title": "failure_category is always 'unknown' — error message classification not working",
      "severity": "High",
      "category": "Product",
      "reproduction": "Run: `./b4w.ps1 experience save \"https://blocked.example.com/search\" '{\"url\":\"...\",\"errorMessage\":\"CAPTCHA detected on page\",...}' --outcome failure --intent \"search for product\"`",
      "expected": "failure_category should be \"anti_bot\" (classified from the CAPTCHA error message) or \"timeout\" (from timeout messages).",
      "actual": "failure_category is always \"unknown\" regardless of the error message content. Tested with \"CAPTCHA detected on page\" and \"Timeout waiting for selector\" — both produced \"unknown\".",
      "rootCause": "The failure classification logic either does not exist, is not invoked, or its pattern-matching rules don't cover the error messages being tested. The errorMessage field from the trace is stored but not parsed for categorization.",
      "codePointer": "",
      "suggestion": "- Implement pattern-matching on the errorMessage field to classify failures into anti_bot, timeout, selector_not_found, network_error, etc.\n- Add a --failure-category option to allow users to explicitly set the category\n- If classification is intentionally deferred, document that failure_category will be 'unknown' unless explicitly provided"
    },
    {
      "title": "Auto-classified intent is 'other' instead of matching taskType when --intent is omitted",
      "severity": "High",
      "category": "Product",
      "reproduction": "Run: `./b4w.ps1 experience save \"https://example.com/login\" '{\"url\":\"...\",\"taskType\":\"login\",...}'` (no --intent flag).",
      "expected": "Intent should be auto-classified as \"login\" based on the trace's taskType field.",
      "actual": "Intent is classified as \"other\". The taskType field in the trace JSON is not used for intent classification.",
      "rootCause": "The auto-classification logic does not read the taskType field from the trace JSON. It may only use a default fallback value ('other') when no --intent is provided.",
      "codePointer": "",
      "suggestion": "- Read the taskType field from the trace JSON and map it to the corresponding intent (login→LOGIN, extract_product_detail→EXTRACT, search→SEARCH, fill_form→FILL_FORM, etc.)\n- Add a taskType-to-intent mapping table as a fallback when --intent is not explicitly provided"
    },
    {
      "title": "task_type field missing from save response despite --task-type flag being passed",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Run: `./b4w.ps1 experience save \"...\" '...' --task-type \"extract_product_detail\"`\nCheck the JSON response for a task_type field.",
      "expected": "Response should include `\"task_type\": \"extract_product_detail\"` reflecting the explicitly passed --task-type value.",
      "actual": "The task_type field is absent from the JSON response. The --task-type flag is accepted without error but its value is not echoed back in the response.",
      "rootCause": "The --task-type value is stored internally (likely in the trace file) but the response serializer does not include it in the output. Either the field is missing from the response DTO or the save method doesn't propagate it.",
      "codePointer": "",
      "suggestion": "- Add task_type to the save response DTO and populate it from the stored value\n- If task_type is intentionally storage-only, document that it won't appear in the response"
    },
    {
      "title": "--page-size flag is ignored — always defaults to 20",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Run: `./b4w.ps1 experience list --page 1 --page-size 2` when more than 2 entries exist.",
      "expected": "At most 2 entries returned with page_size: 2 in metadata.",
      "actual": "All 3 entries returned with page_size: 20 in metadata. The --page-size value is ignored.",
      "rootCause": "The CLI parser or the backend handler is not reading the --page-size option correctly, or the backend always uses a hardcoded default of 20.",
      "codePointer": "",
      "suggestion": "- Fix the argument binding for --page-size in the CLI parser or backend handler\n- Add a validation that --page-size is within the documented range (1–100)"
    },
    {
      "title": "--intent-filter returns entries with non-matching intents",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Run: `./b4w.ps1 experience list --intent-filter \"extract\"` when both extract and fill_form entries exist.",
      "expected": "Only entries with intent=\"extract\" should be returned.",
      "actual": "Both extract and fill_form entries are returned. The intent filter does not properly filter results.",
      "rootCause": "The --intent-filter is either doing a partial/substring match that matches both 'extract' and 'fill_form' (unlikely since 'fill_form' doesn't contain 'extract'), or the filter is not being applied at all to the query. Most likely the filter parameter is parsed but silently dropped before the backend query executes.",
      "codePointer": "",
      "suggestion": "- Fix the intent-filter application logic in the backend list handler\n- Consider whether partial matching vs exact matching is the intended behavior and document it"
    },
    {
      "title": "Malformed URLs accepted without validation in experience save",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run: `./b4w.ps1 experience save \"not-a-valid-url\" '{\"url\":\"not-a-valid-url\",...}'`",
      "expected": "Either normalize the URL or return a clear error about invalid URL format.",
      "actual": "The string \"not-a-valid-url\" is accepted as-is and used as the domain. No validation or normalization occurs.",
      "rootCause": "No URL validation is performed on the <url> argument. The input is treated as an opaque string and used directly as the domain key.",
      "codePointer": "",
      "suggestion": "- Add URL validation (protocol check, domain extraction) to the save command\n- At minimum, warn if the URL doesn't start with http:// or https://\n- Consider normalizing the URL (lowercase hostname, strip fragments) before using as domain"
    },
    {
      "title": "Naming inconsistency: 'experience deep learn' (space) vs 'experience deep-learn' (hyphen)",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "1. Run `./b4w.ps1 help | grep experience` — shows 'experience deep learn'\n2. Run `./b4w.ps1 experience deep-learn --help` — works, but help header shows 'experience deep learn'\n3. Task documentation and SKILL.md both use 'experience deep-learn'",
      "expected": "Consistent naming convention. The help output should match the actual command name. If kebab-case is the convention, it should be 'experience deep-learn' everywhere.",
      "actual": "The help output displays 'experience deep learn' (space-separated) while 'experience deep-learn' (hyphenated) also works. The canonical form is ambiguous.",
      "rootCause": "The CLI parser accepts both forms (space and hyphen) but the help text generator uses the space-separated form. The subcommand registration uses 'deep learn' as the canonical name internally.",
      "codePointer": "",
      "suggestion": "- Standardize on one naming convention (preferably kebab-case: 'experience deep-learn')\n- Update the help text generator to output the hyphenated form\n- Or accept both but document the canonical form clearly"
    },
    {
      "title": "PowerShell wrapper intercepts short flags like -i and -v",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run: `./b4w.ps1 snapshot -i` or `./b4w.ps1 -- snapshot -i`",
      "expected": "The -i flag should be passed to the browser4-cli binary as the interactive snapshot option.",
      "actual": "PowerShell interprets -i as -InformationAction and -v as -Verbose. Even using `--` separator doesn't resolve it for b4w.ps1. The b4w.sh wrapper also fails.",
      "rootCause": "PowerShell's parameter binder processes short flags before passing them to the wrapped CLI binary. The SKILL.md documents this known issue and suggests workarounds (use b4w.bat or quote arguments), but b4w.bat is not present in the repository and b4w.sh doesn't work in this environment.",
      "codePointer": "",
      "suggestion": "- Bundle b4w.bat alongside b4w.ps1 for Windows users\n- Fix b4w.sh to work properly in Git Bash environments\n- Consider using long-form flags (--interactive) as alternatives for commonly intercepted short flags\n- Add a note in the SKILL.md that on Git Bash, b4w.sh may not work and users should use pwsh directly"
    },
    {
      "title": "status_before field missing from some deep-learn responses",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Run deep_learn for the first time on a domain+intent. The response includes status_after but not status_before.",
      "expected": "Both status_before and status_after should be present in every deep_learn response.",
      "actual": "When deep_learn creates knowledge from scratch (no prior status), status_before is absent from the response. It only appears on subsequent calls where a prior status exists.",
      "rootCause": "The response DTO omits status_before when the prior state is null/empty rather than serializing it as null or 'none'.",
      "codePointer": "",
      "suggestion": "- Always include status_before in the response, using null or \"NONE\" when no prior knowledge exists\n- This ensures consistent response schema for API consumers"
    },
    {
      "title": "failure_category field absent from success trace save responses — task expects null",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Save a success trace. The JSON response does not contain a failure_category field.",
      "expected": "Task documentation says failure_category should be null for success outcomes.",
      "actual": "The field is completely absent from the response, not null. API consumers expecting the field to exist (even as null) will get undefined/missing key errors.",
      "rootCause": "The response serializer omits null fields rather than including them with null values (likely Jackson's NON_NULL inclusion or Kotlin's default serialization behavior).",
      "codePointer": "",
      "suggestion": "- Either always include failure_category in responses (null for success, category string for failure)\n- Or update the documentation to state that failure_category is only present for failure outcomes"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — The core save and deep-learn operations function correctly, but list and query are unusable until deep-learn has been manually triggered for each domain+intent pair. Several response fields are missing or incorrect. The E2E pipeline (browser interaction → save → query → deep_learn → list) completes successfully but only after the deep_learn workaround. 3 out of 5+ saved domains are queryable; amazon.com, blocked.example.com, and example.com remain inaccessible via list/query despite valid data on disk.",
    "successRate": "68% — Approximately 22 of 32 verifiable test criteria passed. Major failures: knowledge indexing gap (Critical), failure_category classification (High), intent auto-classification (High), pagination (Medium), intent-filter (Medium).",
    "issuesFound": 11,
    "majorBlockers": "Critical: experience save does not index knowledge for retrieval — list and query return empty results until deep-learn is explicitly run for each domain+intent pair. This makes the primary knowledge retrieval workflow (save → query) non-functional without an extra deep-learn step that is never documented as required.",
    "mostConfusingAspects": "1. experience save returns 'saved: true' with a valid trace_path, but experience list/query return nothing — the disconnect between successful save and failed retrieval is extremely confusing for a first-time user.\n2. The 'experience deep learn' vs 'experience deep-learn' naming inconsistency in help output.\n3. PowerShell flag interception making basic snapshot commands fail — requires reading the SKILL.md warning section carefully.\n4. The default human-readable output format when the task documentation implies JSON output.",
    "mostValuableImprovements": "1. Make experience save immediately index knowledge for list/query — this is the single most impactful fix.\n2. Implement failure_category classification from error messages.\n3. Auto-classify intent from taskType when --intent is omitted.\n4. Fix pagination (--page-size) and intent-filter bugs.\n5. Add URL validation to experience save.",
    "usabilityRating": 5
  }
}
```
