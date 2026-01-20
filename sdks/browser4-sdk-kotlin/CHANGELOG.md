# Changelog - Kotlin SDK

All notable changes to the Browser4 Kotlin SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Version evolution documentation

## [4.5.0] - 2025-01-15

Initial release of Browser4 Kotlin SDK based on OpenAPI specification v1.0.0.

### Added

#### Core Features
- **Local Driver Mode**: Automatically downloads and starts Browser4.jar (no server setup required)
- **Session Management**: Full lifecycle management of browser sessions
- **Navigation**: Navigate to URLs, control browser history
- **AgenticSession**: High-level API for AI-powered browser automation
- **WebDriver Integration**: Direct access to WebDriver-compatible operations

#### Session Operations
- `AgenticSession.getOrCreate()` - Get or create singleton session
- `session.open(url)` - Open and parse pages
- `session.load(url, args)` - Load pages with options
- `session.submit(url)` - Submit URLs to crawl pool
- `session.normalize(url, args)` - Normalize URLs with load arguments
- `session.parse(page)` - Parse pages to documents
- `session.extract(document, fields)` - Extract data using CSS selectors

#### Driver Operations
- `session.getOrCreateBoundDriver()` - Get WebDriver instance
- `driver.navigateTo(url)` - Navigate to URLs
- `driver.selectFirstTextOrNull(selector)` - Extract text by selector
- `driver.click(selector)` - Click elements
- `driver.fill(selector, text)` - Fill input fields
- `driver.press(selector, key)` - Press keys

#### Element Interaction
- Click, fill, type, press keys
- Hover, focus
- Get attributes and text
- Take screenshots

#### Scrolling Support
- `driver.scrollDown()` / `driver.scrollUp()`
- `driver.scrollTo(selector)`
- `driver.scrollToTop()` / `driver.scrollToBottom()`

#### AI-Powered Agent
- `agent.act(instruction)` - Execute actions via natural language
- `agent.run(task)` - Run multi-step autonomous tasks
- `agent.observe(instruction)` - Get page observations and suggestions
- `agent.extract(instruction, schema)` - Extract structured data
- `agent.summarize(instruction)` - Summarize page content

#### Script Execution
- Execute synchronous JavaScript
- Execute asynchronous JavaScript with callbacks

#### Session Control
- Pause and resume sessions
- Delay operations
- Stop execution

#### Configuration
- `LocalDriverOptions` - Configure local driver behavior
  - Custom port
  - Java options (API keys, system properties)
  - Download directory
  - Auto-start/stop

### API Compatibility

- **OpenAPI Version**: 1.0.0
- **Browser4 Core Version**: 4.5.0
- **Minimum Server Version**: 4.5.0

### Dependencies

- Kotlin 1.9+
- Java 17+
- OkHttp for HTTP client
- Jackson for JSON serialization
- SLF4J for logging

### Breaking Changes

None - initial release.

### Known Issues

- Event streaming (SSE) not yet implemented
- Some advanced agent features may require specific LLM configurations

### Documentation

- [README](./README.md) - Getting started guide
- [Examples](../../examples/browser4-examples) - Usage examples
- [API Documentation](../../openapi/openapi.md) - Complete API reference

---

## Version History

| Version | Date | OpenAPI Version | Notes |
|---------|------|-----------------|-------|
| 4.5.0 | 2025-01-15 | 1.0.0 | Initial release |

---

## Migration Guides

### From Pre-release Versions

If you were using pre-release versions, note the following changes:

1. **Package Structure**: All classes are now under `ai.platon.pulsar.sdk`
2. **Local Driver**: Auto-download feature is enabled by default
3. **Session Management**: Use `AgenticSession.getOrCreate()` instead of manual creation

---

## Roadmap

### v4.6.0 (Planned)
- Event streaming (SSE) support
- Batch operations
- Enhanced error handling
- Performance optimizations
- Additional agent capabilities

### v5.0.0 (Future)
- Support for OpenAPI v2.0
- Breaking API improvements based on user feedback
- Enhanced type safety
- Kotlin DSL for browser automation

---

**For support and issues:**
- GitHub Issues: https://github.com/platonai/browser4/issues
- Documentation: https://browser4.io/docs
