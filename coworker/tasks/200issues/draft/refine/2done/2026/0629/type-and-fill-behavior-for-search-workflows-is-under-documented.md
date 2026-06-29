# `type` and `fill` behavior for search workflows is under-documented

**Severity:** Low  
**Category:** Documentation

## Summary
The documentation does not clearly explain the complete search workflow. SKILL.md shows `fill` and `press` but does not mention that `fill` + `press Enter` may not submit forms on all sites, leaving users to discover workarounds through trial and error.

## Steps to Reproduce
1. As a new user, read SKILL.md for guidance on searching a website
2. Follow the documented pattern: `fill` → `press Enter`
3. Discover that `press Enter` does not submit forms on some sites (see related issue)
4. Resort to trial and error to find the correct workflow

## Expected Behavior
A documented, reliable pattern for search: clearly stating when to use `press Enter` vs. clicking the submit button, and noting site-specific caveats.

## Actual Behavior
The workflow requires trial and error. The documented approach (`fill` + `press Enter`) failed; clicking the Go button worked.

## Suggested Improvement
Add a "Common Workflows" section to SKILL.md with patterns like:
- "Search a website: `fill <searchbox-ref> <query>` then `click <submit-button-ref>`"
- Note that `press Enter` may not submit forms on all sites and clicking the submit button is the reliable approach

---

