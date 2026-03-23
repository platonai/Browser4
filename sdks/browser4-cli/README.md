# Browser4 CLI

`@platonai/browser4-cli` — command-line interface for the [Browser4](https://github.com/platonai/Browser4) browser automation platform.

## Installation

```bash
npm install -g @platonai/browser4-cli
```

Or use it without installing:

```bash
npx @platonai/browser4-cli --help
```

## Prerequisites

A running Browser4 server (default: `http://localhost:8182`).

```bash
# Quick start: download and run the latest Browser4 jar
curl -L https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar -o Browser4.jar
java -jar Browser4.jar
```

## Usage

```
browser4 [options] <command>

Options:
  --base-url <url>    Browser4 server URL  (default: http://localhost:8182)
  --session-id <id>   Re-use an existing session
  -V, --version       output the version number
  -h, --help          display help

Commands:
  session create      Create a new browser session (prints session ID)
  session close <id>  Close a browser session
  tool <name> [args]  Call an MCP tool (key=value argument pairs)
```

### Examples

```bash
# Create a session
SESSION=$(browser4 session create)

# Navigate to a URL
browser4 --session-id "$SESSION" tool navigate url=https://example.com

# Take an accessibility snapshot
browser4 --session-id "$SESSION" tool aria_snapshot

# Close the session
browser4 session close "$SESSION"
```

## Development

```bash
# Install dependencies
npm install

# Build TypeScript
npm run build

# Run unit tests
npm test

# Run live E2E tests (requires a running Browser4 server)
npm run test:e2e
```

## License

Apache-2.0 © Browser4 Team
