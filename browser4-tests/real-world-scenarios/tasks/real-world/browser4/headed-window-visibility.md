# headed-window-visibility

This scenario verifies the boundary behavior of headed (visible-window) browser
sessions: `open --headed` must navigate a **visible** browser window. It also
pins the negative boundary: headless sessions must never show a window, and the
CLI must clearly warn when a headed browser process exists without a visible
window (the fallback detection for environments where the window cannot be
shown).

Background: `open --headed` used to navigate a *separate headless* Chrome
instance while the headed window sat on `about:blank` — the session's explicit
display mode was silently ignored by the browser launch. Fixed in
`AbstractPulsarSession.createBoundDriver` (launch with session-level settings
when the session config carries an explicit `browser.display.mode`).

## Setup

- A browser4-cli with the headed-window visibility check is required. The real
  implementation is `browser4_window_state()` in
  `cli/browser4-cli/src/daemon.rs`, invoked by the `open` handler in
  `main.rs` after a successful navigation of a freshly created session (it is
  skipped when `open` only reconnects to an existing session, because the
  display mode is fixed at creation time). Without it, this scenario documents
  the missing detection as a usability finding instead of asserting behavior.
- **Platform boundary:** the detection exists on Windows only. On Unix builds
  the `browser4_window_state()` stub returns `found_browser=false` /
  `headed_window_visible=true`, so neither warning can ever fire on
  Linux/macOS/CI — window visibility must be verified visually there.
- **Scope boundary:** the Windows check enumerates every `chrome.exe` carrying
  `--remote-debugging-port` and a `PULSAR_CHROME` profile marker on the
  machine; it cannot attribute a process to the CLI session that launched it.
  A visible result is reliable; a negative result can be skewed by other
  concurrent Browser4 sessions, which is why the warning text states the
  detection is machine-wide.
- The scenario does not require a specific site; `https://example.com` is used
  as a stable, lightweight target.

## Steps

1. Close any existing sessions, then open a HEADED session:
   `browser4-cli open --headed https://example.com`
2. Verify the command reports the page (URL/title) — the navigation must
   succeed regardless of window visibility.
3. **Verify the window actually shows the page**: the headed Chrome window
   (title `Example Domain - Google Chrome`) must display the page — check
   visually or via the OS window list, AND confirm the session's CDP endpoint
   serves the URL (not `about:blank`):
   - Find the debugging-enabled headed Chrome process and its listening CDP
     port (`Get-NetTCPConnection -State Listen` for the chrome PID), then
     `GET http://127.0.0.1:<port>/json` — a page target whose `url` is
     `https://example.com/` is required.
   - If the only page target is `about:blank`, record it as a regression: the
     session navigated a different (headless) browser instance.
4. If no visible window exists at all, check for the CLI warning (`⚠  A headed
   Browser4 browser process is running, but no visible window was detected.`,
   followed by a machine-wide-detection note) and record the exact warning
   text — the warning is the fallback detection, not the expected outcome.
   Reopening with `open --headed` against an EXISTING (already created)
   session must not trigger it: the check only runs for freshly created
   sessions.
5. Repeat step 1-4 once more (`close` then `open --headed` again) to check
   whether headed visibility is stable across consecutive launches.
6. Open a HEADLESS session (`browser4-cli open --headless https://example.com`)
   and confirm:
   - No visible browser window is created (headed launch must not leak).
   - The headed-window warning does NOT fire — headless mode legitimately has
     no window.
   - The session's browser process was launched with `--headless`.
7. Verify the CLI exits cleanly (exit code 0, no hang) after each open — a
   headed launch must never block the command.

## Acceptance

- `open --headed` shows a visible window whose CDP page target is the requested
  URL (never `about:blank` while the navigation lands elsewhere).
- `open --headless` creates no visible window, and its browser process is
  launched headless.
- The headed-window warning fires only when a headed browser process exists
  without a visible window, and never for headless sessions.
- Navigation succeeds and the command exits cleanly in every step.
- Any deviation (headed window stuck on `about:blank`, headless launch with a
  visible window, warning never firing while no window appears) is recorded as
  a regression.
