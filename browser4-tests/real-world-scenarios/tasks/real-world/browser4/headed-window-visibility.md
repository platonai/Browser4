# headed-window-visibility

This scenario verifies the boundary behavior of headed (visible-window) browser
sessions: the browser must either show a visible window, or the CLI must clearly
warn when it detects a browser process without a visible window. This covers the
known edge case where a headed browser starts (process + CDP endpoint alive,
navigation works) but the window never appears on the desktop — previously a
silent failure that looked like a hang.

## Setup

- A browser4-cli with the headed-window visibility check (`browser_window_visibility`)
  is required. Without it, this scenario documents the missing detection as a
  usability finding instead of asserting behavior.
- The scenario does not require a specific site; `https://example.com` is used
  as a stable, lightweight target.

## Steps

1. Close any existing sessions, then open a HEADED session:
   `browser4-cli open --headed https://example.com`
2. Verify the command reports a page (URL/title) — the navigation must succeed
   regardless of window visibility.
3. Check the command output for the window-visibility warning (`⚠ Browser4
   started a headed browser process, but no visible window was detected.`):
   - If the warning is present, the CLI detected the boundary case and
     documented it — record the exact warning text as the key finding.
   - If the warning is absent, verify a window actually appeared (visually, or
     via the operating system's window list). Either outcome is acceptable, but
     you must determine which one happened and record it.
4. Repeat step 1-3 once more (`close` then `open --headed` again) to check
   whether window visibility is stable across consecutive headed launches, or
   whether it degrades when sessions are chained.
5. Open a HEADLESS session (`browser4-cli open --headless https://example.com`)
   and confirm the command does NOT emit the headed-window warning — headless
   mode legitimately has no window, so the check must not fire there.
6. Verify the CLI exits cleanly (exit code 0, no hang) after each open —
   a headed launch must never block the command, even when the window is
   missing.

## Acceptance

- Navigation succeeds and the command exits cleanly in every step.
- The headed-window warning fires exactly when a headed browser process exists
  without a visible window, and never for headless sessions.
- If the warning never fires AND a window never appears, record it as a
  regression: the detection is missing on this build.
