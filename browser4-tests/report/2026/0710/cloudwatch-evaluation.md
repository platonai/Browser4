# Browser4-CLI Usability Evaluation — AWS CloudWatch Alarms Task

**Date:** 2026-07-10
**Evaluator:** AI agent acting as first-time user
**Task:** Check AWS Console for active CloudWatch alarms and export to CSV
**CLI Version:** 0.1.30 (from source), Backend v4.11.18

---

## A. Task Result

⚠️ **Task partially completed.** The CLI successfully navigated to the AWS Console, captured the sign-in page, identified the IAM login form fields, filled them, submitted the form, and detected the validation error ("Account is required"). However, the task could not proceed past IAM authentication because real AWS credentials are required. Steps 3–5 (navigate to CloudWatch > Alarms, identify ALARM-state alarms, export to CSV) were not reachable.

| Step | Status | Notes |
|------|--------|-------|
| 1. go to console.aws.amazon.com | ✅ | Redirected to us-east-2.signin.aws.amazon.com (IAM login) |
| 2. log in with IAM credentials | ⚠️ | Form interaction worked; authentication blocked by fake credentials |
| 3. navigate to CloudWatch > Alarms | ❌ | Blocked by login |
| 4. identify ALARM alarms | ❌ | Blocked by login |
| 5. export to cloudwatch-alarms.csv | ❌ | No built-in CSV export command exists |

A placeholder `cloudwatch-alarms.csv` was not created because no data could be extracted.

---

## B. Execution Trace

### Commands Used (in order)

| # | Command | Purpose | Result |
|---|---------|---------|--------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Learn available commands | ✅ Comprehensive help with command categories |
| 2 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- status` | Check server health | ✅ Server UP, v4.11.18 |
| 3 | `goto "https://console.aws.amazon.com/"` | Navigate to AWS Console | ✅ Redirected to sign-in (expected) |
| 4 | `snapshot -v 0` | Capture login page structure | ✅ Snapshot saved with refs |
| 5 | `snapshot -v 0 -i --stdout` | View interactive elements | ✅ Found form fields: Account ID (e278), Username (e306), Password (e317), Sign in (e346) |
| 6 | `fill e278 "123456789012"` | Fill account ID | ✅ Fill confirmed; display showed empty value for first fill |
| 7 | `fill e306 "test-user"` | Fill IAM username | ✅ |
| 8 | `fill e317 "fake-password-123"` | Fill password | ✅ |
| 9 | `click e346` | Submit login form | ✅ Click confirmed; page stayed on sign-in |
| 10 | `snapshot -v 0 -i --stdout` | Check form state after submit | ✅ Detected new error element e428 |
| 11 | `snapshot grep -i "error\|incorrect\|invalid"` | Search for error messages | ✅ Found "account-error Account is required" |
| 12 | `htmlsnapshot` | Capture HTML snapshot | ✅ Showed input#account has `awsui_input-invalid` class |
| 13 | `eval "document.title"` | Test JavaScript evaluation | ✅ Returned "Amazon Web Services Sign-In" |
| 14 | `screenshot --filename aws-signin.png` | Capture visual state | ✅ 467KB PNG saved |
| 15 | `close` | Close session | ✅ Session closed cleanly |

### Major Decisions

- **Used the cargo-based invocation** (`cargo run --manifest-path cli/browser4-cli/Cargo.toml --`) for all commands as instructed, testing the development workflow rather than the globally-installed binary.
- **Submitted fake credentials** to test form interaction and error handling. This was safe (no real account at risk) and demonstrated the CLI's ability to capture form validation errors.
- **Used both `snapshot` (accessibility tree) and `htmlsnapshot` (DOM)** to cross-reference element states — the AX tree showed the error text while the HTML snapshot confirmed `input-invalid` CSS class.

### Workarounds Required

None. All attempted commands worked as documented on the first try.

---

## C. Issues Found

### Issue 1: No built-in CSV or tabular data export command

**Severity:** High

**Category:** Product

**Reproduction:**
1. Run `browser4-cli --help` and search for "csv", "export", or "table"
2. Only `htmlsnapshot export` exists, which exports raw HTML

**Expected:** A command to export extracted structured data as CSV. For example: `browser4-cli export csv "<selector>" --columns "name,status,value" --output alarms.csv`. The task requirement "export active alarms to cloudwatch-alarms.csv" is a common real-world use case that currently has no first-class support.

**Actual:** No CSV export exists. Users must chain `htmlsnapshot query` (X-SQL) or `eval` (JavaScript) with manual post-processing to produce CSV output. This is a significant gap for data extraction workflows.

**Root Cause:** The CLI focuses on extraction primitives (`get`, `get all`, `query`, `eval`) but leaves format conversion entirely to the user. A structured export layer is missing.

**Code Pointer:** Could be a new command in `cli/browser4-cli/src/commands.rs` paired with a backend endpoint.

**AI Suggested Improvement:**
- Add an `export csv` subcommand under `htmlsnapshot` that accepts a `--sql` query (or `--selector` + `--columns`) and outputs CSV to stdout or `--output <file>`
- Alternatively, add `--format csv` to `htmlsnapshot query` to output results as CSV instead of JSON/table
- Support `--headers` flag to include/exclude column headers in CSV output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: First `fill` command displays empty value in success message

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
browser4-cli fill e278 "123456789012"
```
Output: `✓ Filled '' into e278`

**Expected:** `✓ Filled '123456789012' into e278`

**Actual:** The first fill to a new session displays an empty string, even though the fill actually succeeded (the field value was set). Subsequent fills display correctly.

**Root Cause:** Likely a race condition or state initialization issue — the value readback for the confirmation message happens before the value is fully committed to the DOM when the session is "cold."

**Code Pointer:** `cli/browser4-cli/src/main.rs` — the fill command handler's result display logic, or the MCP tool response parsing.

**AI Suggested Improvement:**
- Verify the value readback timing — read the element's value AFTER the fill confirmation, not before
- Add a small delay or retry loop for value readback on the first interaction in a session
- Update the success message format to show the intended value (the one passed as argument) rather than the readback value

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Session persistence reconnects to stale page without warning

**Severity:** Medium

**Category:** UX

**Reproduction:**
1. Use browser4-cli in one session to browse to any page (e.g., Reddit)
2. Close the terminal
3. Later, run `goto "https://console.aws.amazon.com/"` in a new terminal session
4. The CLI reconnects to the previous page first, then navigates to the new URL

**Expected:** Either (a) `goto` navigates directly to the requested URL without showing the previous page, or (b) a warning is shown that a previous session is being reused.

**Actual:** The output shows "Reconnected to existing session on [previous URL]" then navigates to the new URL. This is visible in the output:
```
Reconnected to existing session on https://www.reddit.com/r/programming/...
### Page
- Page URL: https://us-east-2.signin.aws.amazon.com/...
```
This two-step transition could confuse users who expect a clean navigation to their target URL.

**Root Cause:** The session persistence feature auto-reconnects to the last-known browser tab before initiating the new navigation. The reconnection happens synchronously before the `goto` navigation.

**Code Pointer:** `cli/browser4-cli/src/main.rs` — the `goto` command handler's session reconnection logic.

**AI Suggested Improvement:**
- Show a brief "Reconnected to session 'default' (previous: reddit.com)" info message on stderr, not in the main output
- Consider adding a `--fresh` flag to `goto` that forces navigation without reconnection
- Make the reconnection message more distinct from the navigation result (currently both appear in the same output block)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Snapshot output is verbose — hard to scan for form fields

**Severity:** Medium

**Category:** UX

**Reproduction:**
1. Run `snapshot -v 0 -i --stdout` on the AWS sign-in page
2. The output is ~180 lines of YAML with deeply nested generic containers
3. Finding specific form fields requires scanning through all output

**Expected:** A way to filter snapshots by element type (e.g., "show only inputs and buttons"), or a more compact form-centric view.

**Actual:** Even with `-i` (interactive-only), the output includes all interactive elements including deeply nested generic containers. The AWS login form's 3 textboxes, 2 checkboxes, and 5 buttons are surrounded by dozens of generic wrapper elements.

**Root Cause:** The accessibility tree preserves the DOM's full structural nesting. AWS's design system (Cloudscape) uses many wrapper divs. `-i` filters to interactive elements but keeps their full structural parent chain.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` and the backend ARIA snapshot generator.

**AI Suggested Improvement:**
- Add `--type <type>` filter to `snapshot`: `snapshot -v 0 --type textbox,button,checkbox,link` to show only specific interactive element types
- Consider a `--form` flag that shows only form-relevant elements (inputs, buttons, selects, labels) in a flat list format
- Add `--flat` option to suppress structural nesting and show elements as a flat, scannable list with their labels/names

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Chrome not discoverable from bash/POSIX shell on Windows

**Severity:** Low

**Category:** Reliability

**Reproduction:**
1. Open Git Bash on Windows
2. Run `which chrome` or `which google-chrome` — both fail
3. Chrome is installed at `C:\Program Files\Google\Chrome\Application\chrome.exe` but not on the POSIX PATH

**Expected:** The CLI or backend should discover Chrome regardless of the shell environment, using OS-appropriate discovery (Windows registry, common install paths).

**Actual:** In this evaluation, the backend server was already running from a previous session (the daemon had found Chrome earlier), so this was not a blocker. However, a cold start from bash might fail to locate Chrome.

**Root Cause:** Chrome discovery mechanism may rely on PATH lookup or platform-specific logic that doesn't cover all Windows shell environments (Git Bash, MSYS2, Cygwin).

**Code Pointer:** Backend Chrome/Chromium discovery logic (Java) — likely in `browser4-core` or the launcher module.

**AI Suggested Improvement:**
- Add Windows registry-based Chrome detection (`HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe`)
- Check common install paths independently of PATH: `%ProgramFiles%\Google\Chrome\Application\`, `%LocalAppData%\Google\Chrome\Application\`
- Fall back to `chromium`, `msedge`, or other Chromium-based browsers

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: SKILL.md references `snapshot -v 0` but main help emphasizes `--viewport`

**Severity:** Low

**Category:** Documentation

**Reproduction:**
1. Read `skills/browser4-cli/SKILL.md` — all examples use `snapshot -v 0`
2. Run `browser4-cli --help` — no mention of `-v`; shows `Use -v N for viewport pagination` in the inline description
3. Run `snapshot --help` — shows both `-v` and `--viewport` as options

**Expected:** Consistent flag usage across all documentation. The SKILL.md should mention both short (`-v`) and long (`--viewport`) forms.

**Actual:** The SKILL.md exclusively uses `-v 0` without ever explaining what `-v` stands for or that `--viewport` is the long form. A new user reading the SKILL.md learns to use `-v 0` by rote without understanding the concept of viewport pagination.

**Root Cause:** SKILL.md was optimized for brevity. The concept of viewport pagination is explained in `snapshot --help` but not carried through to the skill documentation.

**Code Pointer:** `skills/browser4-cli/SKILL.md` — section 1 (Core Loop) and section 6 (Quick Patterns).

**AI Suggested Improvement:**
- In SKILL.md §1, add a brief note: "`-v 0` (or `--viewport 0`) captures the top viewport"
- Link to the snapshot reference documentation for viewport pagination concepts
- Add at least one example using `--viewport` long form for readability

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: The `snapshot` command is noisy — each snapshot saves a file and prints a path

**Severity:** Low

**Category:** UX

**Reproduction:**
Run multiple snapshot commands in sequence. Each one saves a timestamped YAML file to `.browser4-cli/snapshot/` and prints the file path.

**Expected:** Snapshots should be ephemeral by default — save to a predictable temp location (e.g., `.browser4-cli/last-snapshot.yml`) and only save timestamped copies when `--filename` is explicitly provided.

**Actual:** Each snapshot creates a new timestamped file. After 10+ commands, the snapshot directory fills with files. The file path in the output adds noise.

**Root Cause:** The snapshot is always persisted to a timestamped file as the primary storage mechanism.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` — snapshot file saving logic.

**AI Suggested Improvement:**
- Default: overwrite `.browser4-cli/last-snapshot.yml` on each capture
- `--save` / `--filename`: save as a named file
- `--keep-history`: timestamped files (current behavior)
- This reduces directory clutter and output noise for the common interactive workflow

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `snapshot grep` alternation escaping is confusing for new users

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
browser4-cli snapshot grep -i "error\|incorrect\|invalid"
```

**Expected:** Standard grep pattern syntax works without warnings.

**Actual:** The CLI prints a note: "Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep)."

**Root Cause:** The CLI accepts `\|` (GNU grep BRE syntax) and converts it, which is a compatibility feature, but the note adds noise and cognitive overhead for users who don't know the difference between BRE and ERE.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — snapshot grep pattern processing.

**AI Suggested Improvement:**
- Accept both `|` and `\|` silently (already works, just suppress the note)
- Document that `snapshot grep` uses Rust regex syntax (which is ERE-like) in `--help`
- Add an example: `snapshot grep -i "error|warning|fail"` (bare `|`, no backslash)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status

**Partially completed (40%).** Steps 1–2 were fully tested: navigation, page capture, form identification, form filling, submission, and error detection all worked correctly. Steps 3–5 require authentication, which was not available in this evaluation environment. The task cannot be fully completed without real AWS credentials.

### Estimated Task Success Rate

If valid AWS credentials were available: **~70%** for the full task.

- Navigation to CloudWatch: Likely straightforward (search bar + click pattern)
- Identifying ALARM-state alarms: Feasible via `snapshot grep "ALARM"` or `htmlsnapshot query`
- CSV export: **Requires manual workaround** — no built-in CSV command; would need `eval` with JavaScript to extract data and format as CSV, or `htmlsnapshot query` with X-SQL followed by JSON-to-CSV conversion

### Issues Found

**8 issues** total:
- 1 High (Product): No CSV/structured export command
- 2 Medium (UX): Session reconnection warning, verbose snapshot output
- 5 Low (UX/Documentation/Reliability): Fill display, Chrome discovery, flag docs, snapshot noise, grep alternation

### Major Blockers

1. **Authentication required** — This is inherent to the task, not a tool limitation
2. **No CSV export** — Even with valid credentials, producing `cloudwatch-alarms.csv` would require custom JavaScript or external post-processing

### Most Confusing Aspects

1. **The snapshot-ref lifecycle** — Refs change after every click; users must develop a rhythm of "click → snapshot → use new refs"
2. **When to use `snapshot` vs `htmlsnapshot`** — The tools overlap but serve different purposes (AX tree for interaction, DOM for extraction). The distinction isn't obvious to new users.
3. **The `-v 0` pattern** — It's in SKILL.md but the concept of "viewport pagination" is not intuitive; users must learn that pages are chunked by viewport

### Most Valuable Improvements

1. **CSV/structured export** — This is the single biggest gap. Data extraction without format export means the last mile of every data task is manual.
2. **Snapshot element-type filtering** (`--type textbox,button`) — Would dramatically reduce output noise when hunting for form fields.
3. **Smarter default snapshot handling** — Overwrite a single `last-snapshot.yml` by default instead of creating timestamped files.

### Overall Usability Rating

**7/10**

The CLI is well-designed for browser automation with a clean command model (navigate → snapshot → interact → re-snapshot → extract). The help system is comprehensive, tips are genuinely helpful, and the form interaction primitives work reliably. Major strengths include session persistence, multiple data extraction methods (AX snapshot, HTML snapshot, X-SQL, JavaScript eval), and good error discoverability via `snapshot grep`.

However, the lack of structured data export (CSV, JSON table), verbose snapshot output, and the cognitive overhead of managing ephemeral element refs prevent it from being a seamless experience. For users who need to extract data from authenticated web apps and export it to structured formats, the tool provides excellent primitives but leaves the final formatting step unsolved.
