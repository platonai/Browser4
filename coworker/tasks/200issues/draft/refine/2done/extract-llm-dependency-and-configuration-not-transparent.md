# `extract` LLM dependency and configuration not transparently documented

## Summary
The `extract` command depends on an LLM API key being configured, but the documentation does not clearly explain which providers are supported, how to configure keys, or what error to expect when keys are missing. The command works silently if a key was pre-configured (e.g., on the server), but a first-time user without configuration will encounter an undocumented failure mode.

## Steps to Reproduce
1. Set up a fresh `browser4-cli` installation without an LLM API key
2. Run `browser4-cli extract "get the page title"`
3. Observe the error (or lack thereof)

## Expected Behavior
The documentation (SKILL.md or `extract --help`) should clearly state:
- Which LLM providers are supported
- How to configure API keys (environment variables, config files)
- The exact error message or behavior when no key is configured
- A way to check whether AI features are available

## Actual Behavior
SKILL.md mentions "Requires LLM API key configured" with a pointer to `references/agent.md`, but the error behavior when unconfigured is not documented. If the server happens to have a key pre-configured, the command works without the user understanding why.

## Suggested Fix
1. Document supported LLM providers and key configuration in both SKILL.md and CLI help
2. Add an `extract --check-config` or `browser4-cli status` command that reports whether AI features are available
3. Ensure the error message when no key is configured is clear and actionable

Labels: documentation, discoverability, low
