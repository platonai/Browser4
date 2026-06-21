# Test fixture server (`localhost:18080`) not documented or discoverable

URL: https://github.com/platonai/Browser4/issues/461
State: OPEN
Author: galaxyeye
Assignees: galaxyeye
Labels: enhancement, discoverability, documentation, high
Created: 06/19/2026 22:19:58
Updated: 06/19/2026 22:21:48


## Summary
When following task instructions that reference `http://localhost:18080/generated/form-filling.html`, there is no server running on that port and no documentation explaining how to start one. Users must manually locate the HTML fixtures in the source tree and start their own HTTP server. This is a significant onboarding friction point for anyone evaluating the tool against its test fixtures.

## Steps to Reproduce
1. Follow a task instruction: "Navigate to `http://localhost:18080/generated/form-filling.html`."
2. No server is running; the URL returns a connection refused error.
3. Search for documentation on how to start the test server — none exists.

## Expected Behavior
Either:
- The test fixture server should be auto-started by the CLI when needed, or
- Documentation should clearly explain how to set up and serve the test fixtures (e.g., the location of `mcp-tool-controller-form-fixture.html` in the `browser4-tests` source tree and the command to serve it).

## Actual Behavior
Had to manually locate `mcp-tool-controller-form-fixture.html` in the `browser4-tests` source tree, then start a Python HTTP server to serve the fixture directory at the expected path.

## Suggested Fix
Document how to start the test fixture server, or add a `--fixture` flag or similar mechanism to auto-serve test pages at the expected URL.


