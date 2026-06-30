# Error Handling & Recovery

## Command Exit Codes

- Commands requiring the backend (`open`, `attach`, `goto`, `snapshot`, `click`, etc.) exit non-zero if the backend is unreachable. Check with `browser4-cli list`.
- `attach` exits non-zero when it cannot find the target browser (no matching channel, no CDP endpoint listening).
- `eval` exits non-zero when the JS expression throws.
- `snapshot` exits non-zero when the page isn't ready or the accessibility tree can't be captured.

## Recovery Patterns

| Symptom | Likely cause | Recovery |
|---------|-------------|----------|
| `snapshot` exits non-zero | Page not loaded / stale session | `browser4-cli wait --load=networkidle` then retry |
| `click <ref>` fails | Ref is stale (page changed) | Re-snapshot, use new ref |
| `fill <ref>` does nothing | Element not focused or wrong ref | `click <ref>` first, then `type "<value>"` |
| `domsnapshot get` returns `[]` | CSS selector mismatch in serialized DOM | Fall back to `eval --json` or X-SQL against live DOM |
| `eval` quoting errors (Windows) | Shell mangles nested quotes | Use `--stdin` or `--file` instead of inline JS |
| Backend unreachable | Browser4 server not running | Run `browser4-cli goto <url>` to auto-start |
| Stale session after long idle | Session timed out | Use `goto` instead of manual session management; `goto` auto-reopens |
| CAPTCHA / rate-limit page | Too many rapid requests | Add `wait 2000-3000` between navigations; reduce request rate |
| `snapshot -v 0` shows blank/loading page | Page not finished rendering | `wait --load=networkidle` before snapshot |
