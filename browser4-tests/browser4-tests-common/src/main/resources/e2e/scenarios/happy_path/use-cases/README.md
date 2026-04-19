# Use Cases for Browser4 Agent Testing

This directory contains use case files for end-to-end testing of the Browser4 agent.

## Directory Structure

Each `.txt` file represents a single use case that can be executed by the Browser4 agent.

## File Format

Each use case file contains:
- **Comment lines** (starting with `#`) - Description and metadata
- **Task content** - The actual steps for the agent to execute

### Example:
```
# Use Case 1: E-commerce Product Comparison (Single-site)
# Level: Simple
# Type: Single-site, deterministic
# Description: Compare mechanical keyboards on Amazon

1. go to https://www.amazon.com/
2. search for "mechanical keyboard"
3. open the first 3 products
4. extract price, rating, and review count
5. write a comparison table to a markdown file
```

## Use Case Levels

- **Simple** (Level 1): Single-site, deterministic workflows
- **Complex** (Level 2): Agentic reasoning, loops, aggregation, cross-site workflows
- **Enterprise** (Level 3): Long-running, auditable, SSO authentication

## Running Tests

Use `run-e2e-agents.sh` to execute these use cases:

```bash
# Run all use cases
bin/tests/test-cases/run-e2e-agents.sh

# Run specific use cases by number
bin/tests/test-cases/run-e2e-agents.sh -t "01,02,03"

# Run with verbose output
bin/tests/test-cases/run-e2e-agents.sh --verbose

# Show help
bin/tests/test-cases/run-e2e-agents.sh --help
```

## Adding New Use Cases

1. Create a new `.txt` file with a numbered prefix (e.g., `15-new-use-case.txt`)
2. Add comment lines for description and metadata
3. Add the task steps (numbered list)

The test runner will automatically discover and execute new use cases.

## Reference

These use cases are derived from `docs-dev/agentic/use-cases.md`.
