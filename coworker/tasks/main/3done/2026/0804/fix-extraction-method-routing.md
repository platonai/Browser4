# Issues: extraction-method-routing

> **Source:** `20260804-132436-extraction-method-routing.full.md` | **Date:** 20260804-132436 | **Mode:** dev

## Scenario Background

### Task

All 7 acceptance criteria were completed:

| AC | Method | Status | Result |
|----|--------|--------|--------|
| AC1 | `goto` → `snapshot` → `fill`/`select`/`check`/`click` → `eval` extraction | ✅ | Form submitted; confirmation extracted via live DOM eval (Jane Smith, jane.smith@example.com, US, phone, 3 topics) |
| AC2 | `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` | ✅ | "4K OLED TV 55" |
| AC3 | `goto` → `htmlsnapshot` → `htmlsnapshot inspect` → `htmlsnapshot get all text "div.product-title"` | ✅ | 6 product titles as JSON array |
| AC4 | `htmlsnapshot query --sql` with `DOM_LOAD_AND_SELECT('.product-card')` | ✅ | 6 correlated rows (title, price, URL) |
| AC5 | `eval --stdin --json` with live DOM queries | ✅ | Structured JSON with pageTitle, buttonCount (2), linkCount (0), formCount (0), 5 headings |
| AC6 | `extract "Return product title, price, rating..."` | ✅ | Title "$199.99", rating "4.4", 2 feature bullets extracted via LLM |
| AC7 | `crawl --seed-file` 6 URLs `--depth 0 --sql --format table --refresh` | ✅ | 6 rows with URL, title, price in aligned table |

### Execution Context

**Key Commands:**

**Major steps:**
1. Verified working directory, ran `./b4w.ps1 help`, read SKILL.md and all reference files
2. Confirmed MockSite running on localhost:18080
3. **AC1:** Navigated to form-filling page; used `snapshot -i` for refs; filled fields via `fill`/`select`/`check`; hit `select` silently-failing issue with "Canada" (not in options); switched to eval-based fill+submit; extracted confirmation via `eval`
4. **AC2:** Navigated to product page; `htmlsnapshot` + `get text "#productTitle"` → "4K OLED TV 55"
5. **AC3:** Navigated to listing page; `htmlsnapshot inspect` discovered `div.product-title`; `get all text` returned 6 titles
6. **AC4:** Wrote X-SQL to `.test-sessions/ac4-extract-products.sql`; `htmlsnapshot query --sql @file` returned 6 correlated rows
7. **AC5:** Navigated to interactive page; `eval --stdin --json` computed structured live-DOM data
8. **AC6:** Navigated to product page; `extract` returned title/price/rating/features via LLM (key was configured)
9. **AC7:** Created seed file (6 URLs) and X-SQL file; `crawl` returned 6 aligned rows in table format

**Workarounds required:**
- AC1: Used `eval --stdin` instead of ref-based `fill`/`select`/`click` for reliable form submission due to `select` silently failing and ref staleness
- AC1: Used `eval` for extraction instead of `htmlsnapshot get text` because htmlsnapshot returns stale initial HTML for JS-updated DOM
- AC3: Used `htmlsnapshot inspect` to discover the actual selector (`div.product-title`) instead of the task-suggested `[class*="product-title"]`

```json
{
  "issues": [
    {
      "title": "htmlsnapshot returns stale initial HTML for JavaScript-updated DOM content",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1. Navigate to form-filling page\n2. Fill and submit the form via eval (confirmed submission via live DOM)\n3. Run `htmlsnapshot` then `htmlsnapshot get text \"#result-data\"`\n4. Result is \"No submission yet.\" (the initial HTML state)\n5. Meanwhile `eval` on live DOM shows the actual submission payload",
      "expected": "htmlsnapshot should capture the current live DOM state, including content dynamically updated by JavaScript after page load.",
      "actual": "htmlsnapshot captures and returns the initial server-rendered HTML. Any content added or modified by JavaScript (form submission results, dynamic updates) is not reflected in the stored snapshot. The `#result-data` element always shows its initial text \"No submission yet.\" regardless of actual form submission state.",
      "rootCause": "The htmlsnapshot capture mechanism appears to fetch the raw HTML from the server rather than serializing the current live DOM. The backend's page storage likely uses the initial HTTP response body rather than the browser's current DOM tree. Investigation needed: check whether the backend uses `DOM.getDocument` (CDP) or the HTTP response body for htmlsnapshot storage.",
      "codePointer": "browser4-rest likely in the htmlSnapshot capture handler — check whether it serializes live DOM vs. stored HTTP response",
      "suggestion": "- Use CDP's DOM.getDocument or DOM.getOuterHTML to capture the current live DOM state instead of the initial HTTP response body\n- Add a `--live` flag to explicitly opt into live-DOM capture for dynamic pages\n- Document clearly in SKILL.md that htmlsnapshot captures the initial page HTML and may not reflect JS-updated content; direct users to eval for dynamic data"
    },
    {
      "title": "select command silently fails when option text doesn't match any available option",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. Navigate to form-filling page\n2. Run `select e1613 \"Canada\"` where the country dropdown only has \"-- select --\" and \"United States\"\n3. Output shows `[]` followed by `✓ Selected 'Canada' in e1613`\n4. The actual dropdown value remains unchanged",
      "expected": "The select command should report an error or warning when the requested option text doesn't match any available option, or should list the available options for the user to choose from.",
      "actual": "The command outputs `[]` (empty array, presumably the filtered options list) but then reports success with a checkmark: `✓ Selected 'Canada' in e1613`. The selection silently fails — no value is actually set on the dropdown.",
      "rootCause": "The select command appears to filter the available options by the provided text, gets an empty result, but still reports success. The success/failure check likely only verifies the command was sent, not that the option value was actually applied to the element.",
      "codePointer": "Likely in PulsarWebDriver.kt select() method or the MCP tool handler for select — needs to verify the option was actually found and selected before reporting success",
      "suggestion": "- When the filter returns empty results, report an error with the available options listed\n- Change output from `✓ Selected` to `✗ No option matching \"X\" found. Available: [...]` \n- Consider accepting both option text AND option value for select commands"
    },
    {
      "title": "extract command output wrapped in Java debug string, not clean structured data",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. Navigate to a product page\n2. Run `extract \"Return product title and price as JSON\"`\n3. Output file contains: `{\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"success: true message: OK data: {...}\"}`\n4. The actual extracted data is embedded inside a stringified `description` field",
      "expected": "The extract command should return clean, parseable JSON with the extracted data as top-level fields (e.g., `{\"title\":\"...\", \"price\":\"...\"}`), or at minimum place the data in a dedicated `data` field rather than a stringified `description` field.",
      "actual": "The output is a Java object serialization containing a `description` field that concatenates status messages with the actual JSON data. The entire result is wrapped in: `{\"type\":\"ai.platon.pulsar.agentic.ExtractResult\",\"description\":\"success: true message: OK data: {...}\"}`. This is not machine-parseable without extracting and re-parsing the data substring from description.",
      "rootCause": "The ExtractResult Java class's default serialization (likely via toString() or Gson with the class structure) embeds the actual LLM response data inside a description field rather than promoting it to the top level. The CLI then writes this raw serialization to the output file.",
      "codePointer": "browser4-agentic module — ExtractResult class serialization; and/or the CLI's extract command handler that writes the result",
      "suggestion": "- Add a dedicated `data` field (JSON object) to the ExtractResult serialization separate from `description`\n- Consider using `--json` flag to output only the extracted data object when in machine-readable mode\n- At minimum, document the output format in agent.md so users know to expect wrapped output"
    },
    {
      "title": "eval --file returns null when page context is stale without clear diagnostic",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "1. Navigate to a page\n2. Perform several interactions (fills, clicks) over multiple turns\n3. Run `eval --file script.js`\n4. Returns `null` with message: \"The page context may be stale. Try re-navigating with: goto <url>\"",
      "expected": "eval should either maintain a valid page context across interactions, or provide a clear error code/message that can be handled programmatically, distinguishing between: stale context, JS syntax error, and JS returning null/undefined.",
      "actual": "The error message groups all failure modes into one ambiguous message. There's no way to distinguish between a genuine stale context vs. a JavaScript error in the eval'd code vs. the JS legitimately returning null. The suggested fix (re-navigate) may lose form state.",
      "rootCause": "The eval handler likely checks whether the page context is still valid and returns null with a generic message. It doesn't attempt to distinguish between different failure modes or provide error codes that could be used for programmatic recovery.",
      "codePointer": "Likely in the MCP tool handler for eval or the CLI's eval command handler",
      "suggestion": "- Return distinct error codes for: (a) stale page context, (b) JavaScript execution error, (c) null/undefined return value\n- Consider automatically recovering from stale context by re-establishing it transparently\n- Document in SKILL.md that eval context can become stale and when to expect it"
    },
    {
      "title": "Chained form interactions can produce corrupted form state (data concatenation)",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1. Navigate to form-filling page\n2. Fill multiple fields using refs without re-snapshotting between each fill\n3. Use select on country dropdown\n4. Check the serialized state: phoneNumber shows \"555-123-4567CanadaCanada\" instead of \"555-123-4567\"",
      "expected": "Each form interaction should independently target and modify the correct field without cross-contamination.",
      "actual": "The country selection text \"Canada\" got appended to the phone number field value, resulting in \"555-123-4567CanadaCanada\". This suggests the fill/select commands may be targeting wrong elements or the element focus/selection is leaking between commands.",
      "rootCause": "The country select command (selecting \"Canada\") failed silently (no matching option), but the text \"Canada\" appears to have been typed into the phone number field instead. This could be caused by: (a) the `type` fallback in the select implementation sending keystrokes to the wrong focused element, or (b) the phone number field receiving focus during the ref-based interaction sequence and the select command's text search being applied as input.",
      "codePointer": "PulsarWebDriver.kt select() method — check if the implementation falls back to typing text when no option matches, and verify element focus targeting",
      "suggestion": "- Ensure select() never falls back to typing text into an input field when no option matches\n- Add defensive focus management: verify the target element has focus before sending keystrokes\n- Add integration tests for select with non-matching option values"
    },
    {
      "title": "Form submission via click on submit button may not trigger when validators aren't satisfied by CDP-level interactions",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "1. Navigate to form-filling page\n2. Use fill/select/check commands to populate form fields (including country)\n3. Click the submit button\n4. Form does not submit — submitCount remains 0\n5. Serialized state shows validation errors (e.g., Country is required) even though select reported success",
      "expected": "When fields are filled via fill/select/check and the submit button is clicked, the form should submit if all required fields have values that satisfy client-side validation.",
      "actual": "The form's client-side validation rejects the submission because it doesn't recognize the CDP-level field population as valid input. The select command's value change may not trigger the appropriate DOM events (change, input) that the form's validation logic listens for.",
      "rootCause": "The form's JavaScript validation likely listens for DOM events (input, change, blur) to update its validation state. The fill/select commands set values via CDP (Input.insertText, DOM manipulation) which may not trigger all the events the validation framework expects. The click on submit then fires the form's submit handler which checks validation state — finding it still shows errors.",
      "codePointer": "PulsarWebDriver.kt — fill(), select(), check() methods may need to dispatch additional DOM events after setting values",
      "suggestion": "- After setting field values via CDP, dispatch the standard DOM events (input, change, blur) that JavaScript validation frameworks expect\n- Add a `--trigger-events` flag to fill/select/check to explicitly dispatch validation events\n- Document in SKILL.md that form validation may not recognize CDP-level value changes and recommend eval for complex validated forms"
    },
    {
      "title": "SKILL.md §4a decision tree doesn't mention eval as fallback for dynamic pages where htmlsnapshot fails",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read SKILL.md §4a 'Choosing an Extraction Method'. The decision tree guides users to htmlsnapshot for static pages and eval for dynamic JS logic, but doesn't mention that htmlsnapshot can return stale data for pages with JavaScript-updated DOM content.",
      "expected": "The decision tree should note that htmlsnapshot captures the initial HTML and may not reflect JS-updated content; for pages where interaction has modified the DOM, eval is the recommended extraction method.",
      "actual": "The decision tree shows: 'Dynamic/complex JS logic needed? → eval --json'. This implies eval is only needed for computation, not for accessing JS-updated content that htmlsnapshot fails to capture.",
      "rootCause": "The documentation assumes htmlsnapshot captures the live DOM, but it captures the initial server HTML. The distinction between 'static page' and 'dynamic JS logic' in the decision tree doesn't cover the case where interaction has modified the DOM but no complex JS computation is needed.",
      "codePointer": "skills/browser4-cli/SKILL.md §4a — update the decision tree",
      "suggestion": "- Add a branch to §4a: 'Interacted with the page (filled forms, clicked buttons)? → eval for live DOM, htmlsnapshot may be stale'\n- Add a note under htmlsnapshot documentation: 'Captures initial page HTML; for JS-updated content after interaction, use eval'\n- Link to this caveat from the 'Interact first, then extract' workflow pattern"
    },
    {
      "title": "extract returned only 2 feature bullets instead of the requested 3",
      "severity": "Low",
      "category": "Product",
      "reproduction": "1. Navigate to product page B0E000002\n2. Run `extract \"Return the product title, displayed price, rating, and the top three feature bullets as JSON.\"`\n3. Result contains `\"feature_bullets\":[\"Bluetooth 5.2\",\"30h battery\"]` — only 2 bullets",
      "expected": "The extract command should return 3 feature bullets as requested, or indicate that only 2 are available on the page.",
      "actual": "Only 2 feature bullets were returned. The MockSite product page for B0E000002 has 2 feature bullets (Bluetooth 5.2, 30h battery), but the LLM didn't communicate this limitation back clearly.",
      "rootCause": "The page only has 2 feature bullets, and the LLM returned what it found. However, the response doesn't indicate that the count is limited by the page content rather than an extraction failure.",
      "codePointer": "",
      "suggestion": "- The extract command/LLM should note when it returns fewer items than requested because the page has fewer\n- Consider adding a `requested_count` vs `actual_count` field to the extract output for list-type extractions"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 7 acceptance criteria completed. AC1 required workarounds (eval instead of htmlsnapshot for extraction, eval-based form filling instead of pure ref-based interaction). AC6 worked with a configured LLM key.",
    "successRate": "85% — 6 of 7 ACs worked as documented on first attempt. AC1 required significant workarounds due to htmlsnapshot staleness, select silently failing, and form validation not recognizing CDP-level field changes.",
    "issuesFound": 8,
    "majorBlockers": "1) htmlsnapshot captures initial HTML, not live DOM — breaks the 'interact then extract' workflow for JS-updated pages. 2) Form interaction via fill/select/check doesn't trigger JS validation events, preventing form submission on validated forms. 3) select command silently fails with non-matching option values.",
    "mostConfusingAspects": "1) The interaction between snapshot refs and htmlsnapshot — they're different DOM views (AX tree vs HTML) and refs can't be used with htmlsnapshot. 2) The eval command returning null with a generic 'stale context' message — it's unclear whether the JS is wrong or the context is actually stale. 3) The select command reporting success when it actually failed to select anything — extremely misleading for debugging.",
    "mostValuableImprovements": "1) Fix htmlsnapshot to capture live DOM instead of initial HTML (or add a --live flag). 2) Make select fail loudly with available options when no match is found. 3) Dispatch JS validation events after fill/select/check operations. 4) Improve extract output format to be clean JSON.",
    "usabilityRating": 6
  }
}
```

---

## Issues Found (8 issues)

### Issue 1: htmlsnapshot returns stale initial HTML for JavaScript-updated DOM content

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. Navigate to form-filling page
2. Fill and submit the form via eval (confirmed submission via live DOM)
3. Run `htmlsnapshot` then `htmlsnapshot get text "#result-data"`
4. Result is "No submission yet." (the initial HTML state)
5. Meanwhile `eval` on live DOM shows the actual submission payload

#### Expected Behavior

htmlsnapshot should capture the current live DOM state, including content dynamically updated by JavaScript after page load.

#### Actual Behavior

htmlsnapshot captures and returns the initial server-rendered HTML. Any content added or modified by JavaScript (form submission results, dynamic updates) is not reflected in the stored snapshot. The `#result-data` element always shows its initial text "No submission yet." regardless of actual form submission state.

#### Root Cause Analysis

The htmlsnapshot capture mechanism appears to fetch the raw HTML from the server rather than serializing the current live DOM. The backend's page storage likely uses the initial HTTP response body rather than the browser's current DOM tree. Investigation needed: check whether the backend uses `DOM.getDocument` (CDP) or the HTTP response body for htmlsnapshot storage.

#### Code Pointer

`browser4-rest likely in the htmlSnapshot capture handler — check whether it serializes live DOM vs. stored HTTP response`

#### AI Suggested Improvement

- Use CDP's DOM.getDocument or DOM.getOuterHTML to capture the current live DOM state instead of the initial HTTP response body
- Add a `--live` flag to explicitly opt into live-DOM capture for dynamic pages
- Document clearly in SKILL.md that htmlsnapshot captures the initial page HTML and may not reflect JS-updated content; direct users to eval for dynamic data

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Chained form interactions can produce corrupted form state (data concatenation)

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Navigate to form-filling page
2. Fill multiple fields using refs without re-snapshotting between each fill
3. Use select on country dropdown
4. Check the serialized state: phoneNumber shows "555-123-4567CanadaCanada" instead of "555-123-4567"

#### Expected Behavior

Each form interaction should independently target and modify the correct field without cross-contamination.

#### Actual Behavior

The country selection text "Canada" got appended to the phone number field value, resulting in "555-123-4567CanadaCanada". This suggests the fill/select commands may be targeting wrong elements or the element focus/selection is leaking between commands.

#### Root Cause Analysis

The country select command (selecting "Canada") failed silently (no matching option), but the text "Canada" appears to have been typed into the phone number field instead. This could be caused by: (a) the `type` fallback in the select implementation sending keystrokes to the wrong focused element, or (b) the phone number field receiving focus during the ref-based interaction sequence and the select command's text search being applied as input.

#### Code Pointer

`PulsarWebDriver.kt select() method — check if the implementation falls back to typing text when no option matches, and verify element focus targeting`

#### AI Suggested Improvement

- Ensure select() never falls back to typing text into an input field when no option matches
- Add defensive focus management: verify the target element has focus before sending keystrokes
- Add integration tests for select with non-matching option values

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: select command silently fails when option text doesn't match any available option

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Navigate to form-filling page
2. Run `select e1613 "Canada"` where the country dropdown only has "-- select --" and "United States"
3. Output shows `[]` followed by `✓ Selected 'Canada' in e1613`
4. The actual dropdown value remains unchanged

#### Expected Behavior

The select command should report an error or warning when the requested option text doesn't match any available option, or should list the available options for the user to choose from.

#### Actual Behavior

The command outputs `[]` (empty array, presumably the filtered options list) but then reports success with a checkmark: `✓ Selected 'Canada' in e1613`. The selection silently fails — no value is actually set on the dropdown.

#### Root Cause Analysis

The select command appears to filter the available options by the provided text, gets an empty result, but still reports success. The success/failure check likely only verifies the command was sent, not that the option value was actually applied to the element.

#### Code Pointer

`Likely in PulsarWebDriver.kt select() method or the MCP tool handler for select — needs to verify the option was actually found and selected before reporting success`

#### AI Suggested Improvement

- When the filter returns empty results, report an error with the available options listed
- Change output from `✓ Selected` to `✗ No option matching "X" found. Available: [...]` 
- Consider accepting both option text AND option value for select commands

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: extract command output wrapped in Java debug string, not clean structured data

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Navigate to a product page
2. Run `extract "Return product title and price as JSON"`
3. Output file contains: `{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"success: true message: OK data: {...}"}`
4. The actual extracted data is embedded inside a stringified `description` field

#### Expected Behavior

The extract command should return clean, parseable JSON with the extracted data as top-level fields (e.g., `{"title":"...", "price":"..."}`), or at minimum place the data in a dedicated `data` field rather than a stringified `description` field.

#### Actual Behavior

The output is a Java object serialization containing a `description` field that concatenates status messages with the actual JSON data. The entire result is wrapped in: `{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"success: true message: OK data: {...}"}`. This is not machine-parseable without extracting and re-parsing the data substring from description.

#### Root Cause Analysis

The ExtractResult Java class's default serialization (likely via toString() or Gson with the class structure) embeds the actual LLM response data inside a description field rather than promoting it to the top level. The CLI then writes this raw serialization to the output file.

#### Code Pointer

`browser4-agentic module — ExtractResult class serialization; and/or the CLI's extract command handler that writes the result`

#### AI Suggested Improvement

- Add a dedicated `data` field (JSON object) to the ExtractResult serialization separate from `description`
- Consider using `--json` flag to output only the extracted data object when in machine-readable mode
- At minimum, document the output format in agent.md so users know to expect wrapped output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: eval --file returns null when page context is stale without clear diagnostic

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. Navigate to a page
2. Perform several interactions (fills, clicks) over multiple turns
3. Run `eval --file script.js`
4. Returns `null` with message: "The page context may be stale. Try re-navigating with: goto <url>"

#### Expected Behavior

eval should either maintain a valid page context across interactions, or provide a clear error code/message that can be handled programmatically, distinguishing between: stale context, JS syntax error, and JS returning null/undefined.

#### Actual Behavior

The error message groups all failure modes into one ambiguous message. There's no way to distinguish between a genuine stale context vs. a JavaScript error in the eval'd code vs. the JS legitimately returning null. The suggested fix (re-navigate) may lose form state.

#### Root Cause Analysis

The eval handler likely checks whether the page context is still valid and returns null with a generic message. It doesn't attempt to distinguish between different failure modes or provide error codes that could be used for programmatic recovery.

#### Code Pointer

`Likely in the MCP tool handler for eval or the CLI's eval command handler`

#### AI Suggested Improvement

- Return distinct error codes for: (a) stale page context, (b) JavaScript execution error, (c) null/undefined return value
- Consider automatically recovering from stale context by re-establishing it transparently
- Document in SKILL.md that eval context can become stale and when to expect it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
find out why the page becomes stale

---

### Issue 6: Form submission via click on submit button may not trigger when validators aren't satisfied by CDP-level interactions

**Severity:** Medium
**Category:** Product

#### Reproduction

1. Navigate to form-filling page
2. Use fill/select/check commands to populate form fields (including country)
3. Click the submit button
4. Form does not submit — submitCount remains 0
5. Serialized state shows validation errors (e.g., Country is required) even though select reported success

#### Expected Behavior

When fields are filled via fill/select/check and the submit button is clicked, the form should submit if all required fields have values that satisfy client-side validation.

#### Actual Behavior

The form's client-side validation rejects the submission because it doesn't recognize the CDP-level field population as valid input. The select command's value change may not trigger the appropriate DOM events (change, input) that the form's validation logic listens for.

#### Root Cause Analysis

The form's JavaScript validation likely listens for DOM events (input, change, blur) to update its validation state. The fill/select commands set values via CDP (Input.insertText, DOM manipulation) which may not trigger all the events the validation framework expects. The click on submit then fires the form's submit handler which checks validation state — finding it still shows errors.

#### Code Pointer

`PulsarWebDriver.kt — fill(), select(), check() methods may need to dispatch additional DOM events after setting values`

#### AI Suggested Improvement

- After setting field values via CDP, dispatch the standard DOM events (input, change, blur) that JavaScript validation frameworks expect
- Add a `--trigger-events` flag to fill/select/check to explicitly dispatch validation events
- Document in SKILL.md that form validation may not recognize CDP-level value changes and recommend eval for complex validated forms

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: SKILL.md §4a decision tree doesn't mention eval as fallback for dynamic pages where htmlsnapshot fails

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read SKILL.md §4a 'Choosing an Extraction Method'. The decision tree guides users to htmlsnapshot for static pages and eval for dynamic JS logic, but doesn't mention that htmlsnapshot can return stale data for pages with JavaScript-updated DOM content.

#### Expected Behavior

The decision tree should note that htmlsnapshot captures the initial HTML and may not reflect JS-updated content; for pages where interaction has modified the DOM, eval is the recommended extraction method.

#### Actual Behavior

The decision tree shows: 'Dynamic/complex JS logic needed? → eval --json'. This implies eval is only needed for computation, not for accessing JS-updated content that htmlsnapshot fails to capture.

#### Root Cause Analysis

The documentation assumes htmlsnapshot captures the live DOM, but it captures the initial server HTML. The distinction between 'static page' and 'dynamic JS logic' in the decision tree doesn't cover the case where interaction has modified the DOM but no complex JS computation is needed.

#### Code Pointer

`skills/browser4-cli/SKILL.md §4a — update the decision tree`

#### AI Suggested Improvement

- Add a branch to §4a: 'Interacted with the page (filled forms, clicked buttons)? → eval for live DOM, htmlsnapshot may be stale'
- Add a note under htmlsnapshot documentation: 'Captures initial page HTML; for JS-updated content after interaction, use eval'
- Link to this caveat from the 'Interact first, then extract' workflow pattern

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
htmlsnapshot should reflect JS-updated content

---

### Issue 8: extract returned only 2 feature bullets instead of the requested 3

**Severity:** Low
**Category:** Product

#### Reproduction

1. Navigate to product page B0E000002
2. Run `extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON."`
3. Result contains `"feature_bullets":["Bluetooth 5.2","30h battery"]` — only 2 bullets

#### Expected Behavior

The extract command should return 3 feature bullets as requested, or indicate that only 2 are available on the page.

#### Actual Behavior

Only 2 feature bullets were returned. The MockSite product page for B0E000002 has 2 feature bullets (Bluetooth 5.2, 30h battery), but the LLM didn't communicate this limitation back clearly.

#### Root Cause Analysis

The page only has 2 feature bullets, and the LLM returned what it found. However, the response doesn't indicate that the count is limited by the page content rather than an extraction failure.

#### AI Suggested Improvement

- The extract command/LLM should note when it returns fewer items than requested because the page has fewer
- Consider adding a `requested_count` vs `actual_count` field to the extract output for list-type extractions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 7 acceptance criteria completed. AC1 required workarounds (eval instead of htmlsnapshot for extraction, eval-based form filling instead of pure ref-based interaction). AC6 worked with a configured LLM key.

**Success Rate:** 85% — 6 of 7 ACs worked as documented on first attempt. AC1 required significant workarounds due to htmlsnapshot staleness, select silently failing, and form validation not recognizing CDP-level field changes.

**Issues Found:** 8

**Major Blockers:** 1) htmlsnapshot captures initial HTML, not live DOM — breaks the 'interact then extract' workflow for JS-updated pages. 2) Form interaction via fill/select/check doesn't trigger JS validation events, preventing form submission on validated forms. 3) select command silently fails with non-matching option values.

**Most Confusing Aspects:** 1) The interaction between snapshot refs and htmlsnapshot — they're different DOM views (AX tree vs HTML) and refs can't be used with htmlsnapshot. 2) The eval command returning null with a generic 'stale context' message — it's unclear whether the JS is wrong or the context is actually stale. 3) The select command reporting success when it actually failed to select anything — extremely misleading for debugging.

**Most Valuable Improvements:** 1) Fix htmlsnapshot to capture live DOM instead of initial HTML (or add a --live flag). 2) Make select fail loudly with available options when no match is found. 3) Dispatch JS validation events after fill/select/check operations. 4) Improve extract output format to be clean JSON.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot returns stale initial HTML for JavaScript-updated DOM content

1. Navigate to form-filling page
2. Fill and submit the form via eval (confirmed submission via live DOM)
3. Run `htmlsnapshot` then `htmlsnapshot get text "#result-data"`
4. Result is "No submission yet." (the initial HTML state)
5. Meanwhile `eval` on live DOM shows the actual submission payload

#### Issue 2: Chained form interactions can produce corrupted form state (data concatenation)

1. Navigate to form-filling page
2. Fill multiple fields using refs without re-snapshotting between each fill
3. Use select on country dropdown
4. Check the serialized state: phoneNumber shows "555-123-4567CanadaCanada" instead of "555-123-4567"

#### Issue 3: select command silently fails when option text doesn't match any available option

1. Navigate to form-filling page
2. Run `select e1613 "Canada"` where the country dropdown only has "-- select --" and "United States"
3. Output shows `[]` followed by `✓ Selected 'Canada' in e1613`
4. The actual dropdown value remains unchanged

#### Issue 4: extract command output wrapped in Java debug string, not clean structured data

1. Navigate to a product page
2. Run `extract "Return product title and price as JSON"`
3. Output file contains: `{"type":"ai.platon.pulsar.agentic.ExtractResult","description":"success: true message: OK data: {...}"}`
4. The actual extracted data is embedded inside a stringified `description` field

#### Issue 5: eval --file returns null when page context is stale without clear diagnostic

1. Navigate to a page
2. Perform several interactions (fills, clicks) over multiple turns
3. Run `eval --file script.js`
4. Returns `null` with message: "The page context may be stale. Try re-navigating with: goto <url>"

#### Issue 6: Form submission via click on submit button may not trigger when validators aren't satisfied by CDP-level interactions

1. Navigate to form-filling page
2. Use fill/select/check commands to populate form fields (including country)
3. Click the submit button
4. Form does not submit — submitCount remains 0
5. Serialized state shows validation errors (e.g., Country is required) even though select reported success

#### Issue 7: SKILL.md §4a decision tree doesn't mention eval as fallback for dynamic pages where htmlsnapshot fails

Read SKILL.md §4a 'Choosing an Extraction Method'. The decision tree guides users to htmlsnapshot for static pages and eval for dynamic JS logic, but doesn't mention that htmlsnapshot can return stale data for pages with JavaScript-updated DOM content.

#### Issue 8: extract returned only 2 feature bullets instead of the requested 3

1. Navigate to product page B0E000002
2. Run `extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON."`
3. Result contains `"feature_bullets":["Bluetooth 5.2","30h battery"]` — only 2 bullets

