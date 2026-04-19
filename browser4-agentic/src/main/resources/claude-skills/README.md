# Claude Skills for Browser4

This directory contains **Claude Skills** - instructional documents that teach AI agents how to perform browser automation tasks using Browser4 tools.

## What are Claude Skills?

Claude Skills are **markdown-based instruction documents** that provide context to AI agents (specifically Claude by Anthropic). They describe **how to use tools** rather than implementing the tools themselves.

### Key Characteristics:
- ✅ **Pure markdown** - no executable code
- ✅ **Natural language instructions** - written for AI understanding
- ✅ **Tool references** - describe how to use existing tools
- ✅ **Examples and best practices** - teach effective patterns
- ✅ **Context for AI** - injected into prompts to guide behavior

## Difference from Programmatic Skills

Browser4 has two types of skill frameworks:

### 1. **Programmatic Skills** (`/skills/`)
- Kotlin class implementations
- Executable code
- Dynamic loading and execution
- For: Developers building automation tools
- Located in: `src/main/kotlin/.../skills/`

### 2. **Claude Skills** (this directory)
- Markdown instruction documents
- Natural language descriptions
- Static context provision
- For: AI agents using the tools
- Located in: `src/main/resources/claude-skills/`

## Available Claude Skills

### Core Browser Automation Skills

#### [web-scraping.md](./web-scraping.md)
Learn how to extract data from web pages using CSS selectors, including handling dynamic content and multiple attributes.

#### [form-filling.md](./form-filling.md)
Learn how to automatically fill and submit web forms, including handling different field types and multi-step forms.

#### [data-validation.md](./data-validation.md)
Learn how to validate extracted or input data against rules, including email validation and custom patterns.

#### [browser-automation.md](./browser-automation.md)
Learn fundamental browser automation tasks including navigation, waiting for elements, clicking, and error handling.

## How to Use Claude Skills

Claude Skills are provided as context when making API calls to guide the AI agent's behavior. See individual skill files for detailed instructions and examples.

## Resources

- **Claude Skills Documentation**: https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview
- **Browser4 Programmatic Skills**: `/skills/`
- **Compliance Review**: `/docs-dev/claude-skills-compliance.md`
- **Skills Framework**: `/docs-dev/copilot/skills-framework.md`
