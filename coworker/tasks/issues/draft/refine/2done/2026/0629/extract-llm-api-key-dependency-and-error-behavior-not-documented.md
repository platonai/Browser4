# `extract` LLM API key dependency and error behavior not documented

**Severity:** Low  
**Category:** Documentation / Discoverability

## Summary
The `extract` command requires an LLM API key to function, but the documentation does not clearly explain which providers are supported, how to configure keys, or what error message to expect when keys are missing. A first-time user without a configured key will encounter a confusing failure.

## Steps to Reproduce
1. Ensure no LLM API key is configured
2. Navigate to any page: `browser4-cli goto "https://example.com"`
3. Run: `browser4-cli extract "get the page title"`
4. Observe: Unclear error behavior (not documented what happens)

## Expected Behavior
The documentation (SKILL.md and/or `--help`) should explain:
- Which LLM providers are supported
- How to configure API keys
- What error to expect if keys are missing
- How to verify AI features are available

## Actual Behavior
SKILL.md says "Requires LLM API key configured" with a pointer to `references/agent.md`, but the error behavior when unconfigured is not documented. The command worked in testing because the server had a key pre-configured, masking the issue.

## Context
Discovered during an Amazon product search evaluation. `extract` was the only reliable content extraction method after `domsnapshot get` and `domsnapshot-get-all` both failed. A user without LLM configuration would be completely blocked from extracting structured data. The opaque dependency creates a hidden failure mode.

## Suggested Improvement
1. Document the exact error message users will see without a configured key
2. Add an `extract --check-config` or `status` command that reports whether AI features are available
3. List supported LLM providers and configuration methods in `--help` output
4. Consider a first-run check that warns if AI features are unavailable

---

