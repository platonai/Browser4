# Snapshot refs require defensive re-scanning after every page interaction

**Severity:** Low  
**Category:** UX

## Summary
After any page interaction (`fill`, `click`, `press`, etc.), element refs may change due to DOM mutations. The documentation advises users to always take a fresh snapshot and re-scan for refs. In practice, this means users must defensively re-scan after every interaction — even when refs are stable. The CLI provides no assistance in tracking ref validity.

## Steps to Reproduce
1. Take a snapshot and note a ref (e.g., search button at `e345`)
2. Perform an interaction on a different element (e.g., `fill` a search box)
3. The ref `e345` might still be valid, or it might not — the CLI provides no indication either way

## Expected Behavior
The CLI could track ref stability across interactions and tell the user "ref e345 is still valid" or warn when refs have likely changed. A `--verify-ref` flag could check validity before executing a command.

## Actual Behavior
Users must defensively re-scan after every interaction, even when refs are stable. The documentation's guidance to "always re-snapshot" is safe but adds friction to every multi-step workflow.

## Context
Discovered during an Amazon product search evaluation. In the session, refs were stable across interactions, but the user still had to `grep` the snapshot file after every step to confirm. For a 10-step workflow, this means ~10 unnecessary file reads when refs were actually stable the entire time.

## Suggested Improvement
1. Add a `--verify-ref` flag to commands like `click` that checks ref validity before executing and warns if stale
2. Track ref stability across the session and print a hint like "ref e345 unchanged since last snapshot"
3. Consider an auto-refresh mode where the CLI re-snapshots automatically before commands that need refs

---

