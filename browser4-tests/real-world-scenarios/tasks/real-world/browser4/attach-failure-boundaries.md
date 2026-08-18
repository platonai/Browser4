# attach-failure-boundaries

This scenario verifies the failure and boundary behavior of `attach --cdp`,
complementing `attach-remote-debug.md` (which covers the happy path). The known
failure modes under test:

- Attach to an unreachable endpoint must FAIL LOUDLY (clear error), never
  report success and silently attach to nothing.
- Attach must verify the endpoint before binding: a reachable-but-page-less
  endpoint is an error, not a silent success.
- After a successful attach, the CLI must report the target browser's REAL
  current page — not a page header produced without evidence the target
  answered.
- `attach --cdp chrome` must discover Browser4-managed browsers even when they
  launched with `--remote-debugging-port=0` (random port).

## Setup

- A browser4-cli build with the CDP verification (attach verify + random-port
  discovery) is required. On an older build, record which checks are missing as
  usability findings instead of asserting them.
- No site is required; `https://example.com` is the stable target.
- The scenario needs a port that is guaranteed NOT to be listening. Use a
  high port unlikely to be in use (e.g. 19999); if the port happens to answer,
  pick another.

## Steps

### A. Unreachable endpoint fails loudly

1. Run `browser4-cli -s bad attach --cdp http://127.0.0.1:19999` (a port with
   nothing listening).
2. The command MUST fail with a non-zero exit and an error message that (a)
   names the endpoint, (b) says it is not reachable, and (c) suggests how to
   start the browser / retry. Record the exact error text.
3. Confirm it did NOT print a page header or "Session opened" — a false success
   here is the critical regression this scenario guards against.

### B. Reachable endpoint without page targets is an error

4. Start a headed or headless browser session (e.g. `browser4-cli open --headless
   https://example.com`) so a Browser4-managed browser exists. Find its actual
   CDP port: on Windows `Get-NetTCPConnection` keyed to the chrome process with
   `--remote-debugging-port`, or `browser4-cli attach --cdp chrome` itself.
5. Attach with an endpoint that is reachable but exposes no page target — for
   example a plain HTTP server, or a Chrome instance with all tabs closed. The
   command MUST fail with a message about zero page targets (or equivalent),
   not report success. Record the exact error text.

### C. Successful attach reports the real current page

6. With the Browser4-managed browser from step 4 still running, attach to its
   real CDP port: `browser4-cli -s good attach --cdp <real-port>`.
7. The output MUST include the browser's actual current page (URL and/or title)
   after "Attached to browser at ...". Verify it matches what the browser is
   really showing (e.g. run `page-info` in the same session and compare).

### D. Random-port discovery via channel name

8. `browser4-cli -s ch attach --cdp chrome` (channel name, no explicit port)
   must find the Browser4-managed browser even though it launched with
   `--remote-debugging-port=0`. It MUST NOT fail with "Could not find a running
   chrome browser".
9. Confirm the attached session is usable (e.g. `page-info` or `tab-list`
   returns the browser's real tabs).

### E. Cleanup

10. Close the test sessions (`browser4-cli -s bad close`, `-s good`,
    `-s ch close`, and any open session) so no test state leaks.

## Acceptance

- Step 2-3: unreachable endpoint fails loudly with actionable error, no false
  success.
- Step 5: reachable-but-page-less endpoint fails, no false success.
- Step 7: successful attach reports the real current page.
- Step 8-9: `attach --cdp chrome` discovers random-port Browser4 browsers.
- Any step that silently succeeds where the scenario expects a failure is a
  regression; record it with the exact command and output.
