# `extract` command requires LLM API key with no discoverable setup path

The `extract` command, which enables structured data extraction using an LLM, requires an API key to be configured — but there is no built-in setup wizard, no `--help` entry showing how to configure the key, and no clear error message listing supported providers when the key is missing.

**Steps to reproduce:**
1. Run `browser4-cli extract "get the first 4 products with title, price, rating"`.
2. Observe the failure.

**Expected behavior:** Either the command works out of the box, or a clear error message guides the user through setup (listing supported providers, required environment variables, and configuration file paths).

**Actual behavior:** The documentation mentions that an LLM API key is required and points to `references/agent.md`, but there is no inline guidance from the CLI itself. A first-time user has no way to discover which providers are supported or how to set the key.

**Suggested improvement:** Add a `browser4-cli config` or `browser4-cli agent --setup` wizard. When the API key is missing, show a clear error message listing supported providers, required environment variables, and configuration file paths.

Labels: enhancement, documentation, ux

