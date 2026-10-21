Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace has not been trusted. Run Claude Code interactively here once and accept the trust dialog, or set projects["D:/workspace/Browser4/Browser4-4.13"].hasTrustDialogAccepted: true in C:\Users\pereg\.claude.json.
"deepseek-v4-flash" is not a model this version of Claude Code recognizes, so auto-compact will keep this session within 200k tokens (the context window it assumes). If the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; to make it recognized, map it in the modelOverrides setting or update Claude Code; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"generate_session_title"}
Evaluation complete. Summary of the session:

## What was done

All 13 scenario steps were executed against `interactive-5.html` using only `./b4w.ps1` commands and verified end-to-end (hover tooltips/cards via computed style, drag via DOM order + page log, dblclick via counters/status, dialogs via result-area text, locator → `#alertBtn` → `get text` round-trip). Evidence artifacts (snapshots, screenshots) are in `.test-sessions/`.

## Key environment note

The MockSite on :18080 was a **stale instance serving HTTP 500 for every fixture page**; restarting it via `./bin/test.ps1 mock-site` (documented) fixed it. The dev-mode backend was also **2 versions behind the checked-out source** (4.13.11-SNAPSHOT bundle vs 4.13.13-SNAPSHOT source) — worth knowing when judging the product issues below.

## Top findings (8 total — full structured report above)

1. **High · Reliability** — Dev-mode backend/session lifecycle is unstable: 4 backend JVMs spawned in ~10 min, sessions died between invocations ("No active browser session" seconds after a successful goto), one command hung >5 min on a port handoff.
2. **High · Product** — A click on the Show Prompt ref physically activated the Show Confirm button (stale coordinates after scrolling/dialog handling) and reported success; a delayed duplicate dispatch then opened the real prompt. No staleness detection or wrong-target warning exists.
3. **Medium · Reliability** — `drag` reported a hard "target occluded" error for a drag that had already succeeded, and a single drag command fired 3 full HTML5 drag cycles.
4. **Medium · UX** — Dialog-triggering clicks behaved nondeterministically (one hung until externally killed with exit 143, the next returned instantly), with no actionable "dialog pending — run dialog-accept" messaging.
5. **Medium · Documentation** — AX snapshots are byte-identical before/after a hover that provably shows a CSS-only tooltip, so the documented "hover → snapshot grep" verification pattern cannot verify hover reveals at all.
6–8. **Low** — `snapshot -i` doesn't match its "interactive elements only" docs; dev-mode version skew is only flagged in `status`; stale MockSite 500s were indistinguishable from real failures.

**Usability rating: 6/10** — the core model (snapshot refs → interact → verify) is sound and well-documented, but the session-lifecycle flakiness, wrong-target click hazard, and snapshot visibility blind spot make first-time use unpredictable.
