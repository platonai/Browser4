---

# Browser4-CLI Usability Evaluation Report

## A. Task Result

### Partially Successful (Estimated 55% success rate)

The task required navigating Wikipedia, extracting structured data, summarizing content, and running an autonomous agent. Results:

| Step | Status | Notes |
|------|--------|-------|
| 1. Navigate to Python page | ✅ | Worked on first attempt |
| 2. Extract with JSON schema (inline) | ✅ | Required `pwsh` directly; `b4w.sh` mangled JSON |
| 3. Extract with custom schema file | ✅ | Same — worked via `pwsh`, not `b4w.sh` |
| 4. Summarize entire page | ✅ | Good quality summary generated |
| 5. Summarize History section only | ⚠️ | `#History` selector captured only "History" text; workaround: used `#mw-content-text` selector + focused prompt |
| 6. Submit autonomous agent task | ✅ | Task submitted, ID returned |
| 7. Poll agent status | ✅ | Polled successfully; completed with statusCode 200 |
| 8. Retrieve agent results | ❌ | Returned `{}` — data lost despite successful completion |
| 9. Compare sync vs async | ✅ | Analysis below |

### Key Data Extracted

**Non-LLM** (`htmlsnapshot get text ".infobox"`):
- Name: Python | First appeared: 20 February 1991 | Developer: Python Software Foundation | Typing: Duck, dynamic, strong | License: Python Software Foundation License

**LLM extract with schema:**
```json
{"name":"Python","first_release_year":1991,"developer":"Python Software Foundation","typing_discipline":"Duck, dynamic, strong; optional type annotations","license":"Python Software Foundation License"}
```

---

## B. Execution Trace

26 commands executed. Major phases:
1. **Discovery:** Read SKILL.md, agent.md references, ran `help`
2. **Initial attempt:** `./b4w.ps1` failed (CRLF); switched to `./b4w.sh`
3. **LLM features failed:** All LLM commands returned langchain4j config error
4. **Non-LLM fallback:** Used `htmlsnapshot get text` which worked perfectly for infobox
5. **Backend recovery:** Required `kill-all` after backend degraded (timeouts everywhere)
6. **Post-restart:** All LLM features worked; agent task completed but returned empty

### Comparison: Synchronous vs Asynchronous Approaches

| Aspect | `extract`/`summarize` (Sync) | `agent run` (Async) |
|--------|------------------------------|---------------------|
| **Latency** | Blocks; ~25-30s for Wikipedia | Returns immediately; ~30-45s total |
| **Output** | Saves to file; structured JSON | Returns inline; `{}` in this test |
| **Error visibility** | Immediate error on stdout | Must poll; error in status JSON |
| **Page context** | Operates on current page only | Can navigate anywhere |
| **Suitability** | Single-page extraction/summarization | Multi-step, multi-page tasks |
| **Reliability** | Worked consistently (after restart) | Failed to return data |

**Recommendation:** Use `extract`/`summarize` for single-page operations where you control the context. Use `agent run` for autonomous multi-step workflows where navigation is part of the task — but verify results carefully.

---

## C. Issues Found (13 total)

### Issue 1: CRLF line endings in b4w.ps1 break direct execution on Linux

**Severity:** High | **Category:** Reliability

- **Reproduction:** `./b4w.ps1 help` → `/usr/bin/env: 'pwsh\r': No such file or directory`
- **Root Cause:** b4w.ps1 uses CRLF line terminators; the kernel treats `\r` as part of the shebang executable name
- **Fix:** Convert to LF line endings; add `.gitattributes` rule `*.ps1 text eol=lf`

---

### Issue 2: LLM features fail with obscure JAR path error instead of 'no API key configured'

**Severity:** Critical | **Category:** Reliability

- **Reproduction:** Without API key: `./b4w.sh extract '...'` → `ServiceConfigurationError: ...langchain4j-http-client-jdk-1.5.0.jar`
- **Root Cause:** No pre-flight LLM config check at tool dispatch level; raw Java exception propagates
- **Fix:** Add LLM config check before invoking any agent tool; catch and translate the error

---

### Issue 3: b4w.sh wrapper mangles JSON arguments containing colons

**Severity:** High | **Category:** Reliability

- **Reproduction:** `--schema '{"type":"object",...}'` → `error: too many arguments: expected 1, received 2`
- **Root Cause:** Shell tokenization splits JSON at colons/commas before b4w.sh's quoting loop
- **Fix:** Add `--schema-file` flag; support `@file.json` syntax

---

### Issue 4: Backend degrades over time requiring kill-all to recover

**Severity:** High | **Category:** Reliability

- **Reproduction:** After ~15 min: all commands timeout; Chrome fails to launch; only `kill-all` recovers
- **Root Cause:** Likely resource leak from failed agent attempts; Chrome process dies without backend detection
- **Fix:** Add browser process heartbeat; auto-restart Chrome; resource cleanup in error paths

---

### Issue 5: Agent task completes with statusCode 200 but returns empty result

**Severity:** Critical | **Category:** Product

- **Reproduction:** `agent result <id>` → `{}` despite statusCode 200, pageContentBytes 510008, event "fields"
- **Root Cause:** instructResults data not serialized into commandResult; extraction step likely failed silently
- **Fix:** Investigate result serialization; surface instructResults when commandResult is empty

---

### Issue 6: Summarize --selector only captures matched element text, not section content

**Severity:** Medium | **Category:** Documentation

- **Reproduction:** `--selector "#History"` → LLM: "content not provided" (selector matched only the `<span>` with text "History")
- **Root Cause:** Wikipedia sections have IDs on heading `<span>` elements; content is in following siblings
- **Fix:** Document selector scope; add Wikipedia-specific example; consider `--section` flag

---

### Issues 7-13: Additional findings

7. **No `--schema-file` flag** for extract — inline JSON fragile across shells (Discoverability, Medium)
8. **Agent silently changes page context** — subsequent commands operate on agent's last page (UX, Medium)
9. **Version mismatch warnings** noisy in dev mode (UX, Low)
10. **htmlsnapshot summary times out** on large pages (Reliability, Low)
11. **Doctor detects LLM issue but tools don't fail-fast** with clear message (Discoverability, Medium)
12. **agent list shows failed tasks as 'queued'** — misleading; no `agent clear` (UX, Low)
13. **Extract output format** undocumented — data embedded in string inside wrapper JSON (Documentation, Medium)

---

## D. Overall Assessment

| Metric | Value |
|--------|-------|
| **Task completion** | Partially Successful |
| **Success rate** | ~55% |
| **Issues found** | 13 (2 Critical, 4 High, 5 Medium, 2 Low) |
| **Usability rating** | **5/10** |
| **Major blockers** | LLM error messages, backend degradation, agent empty results, CRLF shebang |

**Most confusing aspects:** (1) b4w.ps1 vs b4w.sh dichotomy on Linux, (2) --selector semantics for summarize, (3) agent returning `{}` when status shows success, (4) extract output format nesting.

**Most valuable improvements:** (1) Pre-flight LLM config check with clear errors, (2) Fix agent result serialization, (3) Add `--schema-file` for extract, (4) Auto-recovery when browser process dies, (5) Cross-platform line endings.

The non-LLM features (`goto`, `snapshot`, `htmlsnapshot get text`) are solid and reliable. The LLM features have potential but are undermined by poor error handling, inconsistent result serialization, and backend stability issues. A new user without LLM keys configured would hit cryptic JAR-path errors and likely give up before discovering the `doctor` command.
