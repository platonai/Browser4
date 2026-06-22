# No documented search workflow in help or SKILL.md

**Severity:** Medium | **Category:** Discoverability / Documentation

The documentation covers `type`, `fill`, and `press` individually but never connects them into a complete search workflow. A first-time user looking to search a website must piece together the pattern through trial and error.

### Steps to Reproduce

1. Read `help` output and `SKILL.md`
2. Look for guidance on how to perform a web search (focus field → type → submit)

### Expected Behavior

A clear, documented pattern for search interaction: "To search on a website, take a snapshot, click the search box ref, type your query, then press Enter on the search box or click the search button."

### Actual Behavior

The documentation shows `type`, `fill`, and `press` individually but never connects them into a search workflow. The "Example: Form submission" in SKILL.md shows a login form but not a search form.

### Suggested Improvements

1. Add a "Search" example to the SKILL.md Quick Start section.
2. Add a high-level `browser4-cli search "<query>"` command that auto-detects search boxes and performs the full workflow.
3. Include a search workflow in the examples section.

