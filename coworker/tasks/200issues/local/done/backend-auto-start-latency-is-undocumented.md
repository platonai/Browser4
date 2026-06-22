# Backend auto-start latency is undocumented

**Severity:** Low | **Category:** Documentation / UX

When running `browser4-cli goto <url>` with the backend not running, the auto-start feature works seamlessly but takes ~30 seconds with progress updates every 10 seconds. A first-time user may think the process is stuck because the reason for the delay is not explained.

### Steps to Reproduce

1. Run `browser4-cli goto <url>` when the backend is not running

### Expected Behavior

Clear indication of what's happening and expected wait time.

### Actual Behavior

The auto-start worked correctly but took ~30 seconds. The countdown format ("7s/120s") is informative but the reason for the delay isn't explained. A first-time user might think it's stuck.

### Suggested Improvements

1. Add a brief message: "Starting Java backend (first launch may take 30-60s)..."
2. Explain the 120s timeout: "Giving the server up to 2 minutes to start."
3. Consider showing the startup log tail on timeout for debugging.

