# Changelog - Python SDK

All notable changes to the Browser4 Python SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Version evolution documentation

## [0.1.0] - 2025-01-15

Initial beta release of Browser4 Python SDK based on OpenAPI specification v1.0.0.

### Added

#### Core Features
- **Browser4Driver**: Automatic server lifecycle management
  - Auto-download Browser4.jar from releases
  - Auto-start server on custom port
  - Context manager support for clean resource management
  - Process management with graceful shutdown
- **PulsarClient**: Low-level HTTP client for Browser4 API
- **AgenticSession**: High-level session management
- **WebDriver**: WebDriver-compatible browser control

#### Session Management
- `PulsarClient.create_session()` - Create new browser sessions
- `PulsarClient.delete_session(session_id)` - Clean up sessions
- `AgenticSession` - High-level session wrapper with context management

#### Navigation
- `AgenticSession.open(url)` - Open and parse pages
- `AgenticSession.load(url, args)` - Load pages with options
- `AgenticSession.submit(url)` - Submit URLs to crawl pool
- `AgenticSession.normalize(url, args)` - Normalize URLs

#### Data Extraction
- `AgenticSession.extract(page, fields)` - Extract data using CSS selectors
- `AgenticSession.parse(page)` - Parse pages with BeautifulSoup integration
- Built-in support for extracting text, attributes, and HTML

#### WebDriver Operations
- `driver.navigate_to(url)` - Navigate to URLs
- `driver.get_current_url()` - Get current page URL
- `driver.find_element(selector)` - Find elements by CSS selector
- `driver.find_elements(selector)` - Find multiple elements
- `driver.click(selector)` - Click elements by selector
- `driver.fill(selector, text)` - Fill input fields
- `driver.press(selector, key)` - Press keys on elements
- `driver.get_outer_html(selector)` - Get element HTML
- `driver.screenshot(selector)` - Take element screenshots

#### AI-Powered Agent
- `session.act(instruction)` - Execute actions via natural language
- `session.run(task)` - Run multi-step autonomous tasks
- `session.observe(instruction)` - Get page observations
- `session.extract(instruction, schema)` - Extract structured data with schema
- `session.summarize(instruction)` - Summarize page content

#### Browser4Driver Features
- Auto-download from GitHub releases or custom URLs
- Configurable port and Java options
- Environment variable support for API keys
- Progress tracking during downloads
- SHA-256 checksum verification
- Cross-platform support (Windows, macOS, Linux)

### API Compatibility

- **OpenAPI Version**: 1.0.0
- **Browser4 Core Version**: 4.5.0
- **Minimum Server Version**: 4.5.0
- **Python Version**: 3.8+

### Dependencies

- `requests` (>=2.31.0) - HTTP client
- `beautifulsoup4` (>=4.12.0) - HTML parsing

### Configuration

Environment variables supported:
- `BROWSER4_DOWNLOAD_URL` - Custom download URL
- `BROWSER4_JAR_PATH` - Path to existing Browser4.jar
- `BROWSER4_PORT` - Default server port
- `OPENROUTER_API_KEY` - OpenRouter API key for AI features
- `OPENAI_API_KEY` - OpenAI API key for AI features

### Examples

#### Basic Usage
```python
from browser4 import Browser4Driver, PulsarClient, AgenticSession

with Browser4Driver() as driver:
    client = PulsarClient(base_url=driver.base_url)
    session = AgenticSession(client)
    
    page = session.open("https://example.com")
    data = session.extract(page, {"title": "h1", "text": "p"})
    print(data)
```

#### AI-Powered Automation
```python
with Browser4Driver() as driver:
    client = PulsarClient(base_url=driver.base_url)
    session = AgenticSession(client)
    
    result = session.act("click the login button")
    history = session.run("search for 'python' and extract top 5 results")
```

### Known Limitations

- Event streaming (SSE) not yet implemented
- Some advanced agent features require LLM API keys
- WebSocket support not available
- No async/await support (planned for v0.2.0)

### Breaking Changes

None - initial beta release.

### Documentation

- [README](./README.md) - Getting started guide
- [API Comparison](./API_COMPARISON.md) - Comparison with other tools
- [Examples](./examples/) - Usage examples
- [Tests](./tests/) - Test suite with examples

---

## Version History

| Version | Date | OpenAPI Version | Status | Notes |
|---------|------|-----------------|--------|-------|
| 0.1.0 | 2025-01-15 | 1.0.0 | Beta | Initial release |

---

## Migration Guides

### Future v1.0.0 Stable Release

When v1.0.0 is released, expect:
- API stabilization (no breaking changes)
- Full event streaming support
- Enhanced error handling
- Production-ready performance
- Complete test coverage

To prepare:
1. Review deprecation warnings
2. Update to latest beta versions
3. Report issues on GitHub

---

## Roadmap

### v0.2.0 (Planned)
- **Async/await support** for concurrent operations
- **Event streaming** (SSE) support
- **WebSocket** support for real-time updates
- **Batch operations** for efficiency
- **Enhanced error handling** with retry logic
- **Type hints** improvements
- **pytest fixtures** for testing

### v1.0.0 (Stable Release - Planned)
- Production-ready stability
- Full OpenAPI v1.x coverage
- Comprehensive documentation
- Performance benchmarks
- Migration tools

### v2.0.0 (Future)
- Support for OpenAPI v2.0
- Breaking API improvements
- Enhanced agent capabilities
- Advanced browser features

---

## Contributing

Contributions welcome! See our [contribution guidelines](../../CONTRIBUTING.md).

**For support and issues:**
- GitHub Issues: https://github.com/platonai/browser4/issues
- Documentation: https://browser4.io/docs
- PyPI: https://pypi.org/project/browser4/
