# Changelog

All notable changes to the Browser4 OpenAPI specification will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Version evolution documentation

## [1.0.0] - 2025-01-15

Initial stable release of Browser4 WebDriver-Compatible API.

### Added
- **Session Management**: Create, get, and delete browser sessions
- **Navigation**: Navigate to URLs, get current URL, document URI, and base URI
- **Selector-First Operations**: 
  - Check element existence
  - Wait for selectors
  - Find elements by selector
  - Click, fill, and press keys using selectors
  - Get outer HTML and take screenshots by selector
- **Standard WebDriver Element Operations**:
  - Find elements using various strategies
  - Click elements
  - Send keys to elements
  - Get element attributes and text
- **Script Execution**: Execute synchronous and asynchronous JavaScript
- **Execution Control**: Delay, pause, and stop operations
- **Event Management**:
  - Create and manage event configurations
  - Subscribe to events
  - Query events
  - Stream events via Server-Sent Events (SSE)
- **AI-Powered Agent Operations**:
  - Run autonomous agent tasks
  - Observe pages and get actionable insights
  - Execute single actions
  - Extract structured data
  - Summarize page content
  - Clear agent history
- **PulsarSession Integration**:
  - Normalize URLs with load arguments
  - Open pages immediately (bypass cache)
  - Load pages from storage or internet
  - Submit URLs to crawl pool

### API Endpoints

#### Session (3 endpoints)
- `POST /session` - Create session
- `GET /session/{sessionId}` - Get session details
- `DELETE /session/{sessionId}` - Delete session

#### Navigation (3 endpoints)
- `POST /session/{sessionId}/url` - Navigate to URL
- `GET /session/{sessionId}/url` - Get current URL
- `GET /session/{sessionId}/documentUri` - Get document URI
- `GET /session/{sessionId}/baseUri` - Get base URI

#### Selectors (8 endpoints)
- `POST /session/{sessionId}/selectors/exists` - Check selector exists
- `POST /session/{sessionId}/selectors/waitFor` - Wait for selector
- `POST /session/{sessionId}/selectors/element` - Find element by selector
- `POST /session/{sessionId}/selectors/elements` - Find elements by selector
- `POST /session/{sessionId}/selectors/click` - Click by selector
- `POST /session/{sessionId}/selectors/fill` - Fill input by selector
- `POST /session/{sessionId}/selectors/press` - Press key by selector
- `POST /session/{sessionId}/selectors/outerHtml` - Get outer HTML by selector
- `POST /session/{sessionId}/selectors/screenshot` - Screenshot by selector

#### Element (5 endpoints)
- `POST /session/{sessionId}/element` - Find element
- `POST /session/{sessionId}/elements` - Find elements
- `POST /session/{sessionId}/element/{elementId}/click` - Click element
- `POST /session/{sessionId}/element/{elementId}/value` - Send keys to element
- `GET /session/{sessionId}/element/{elementId}/attribute/{name}` - Get element attribute
- `GET /session/{sessionId}/element/{elementId}/text` - Get element text

#### Script (2 endpoints)
- `POST /session/{sessionId}/execute/sync` - Execute synchronous script
- `POST /session/{sessionId}/execute/async` - Execute asynchronous script

#### Control (3 endpoints)
- `POST /session/{sessionId}/control/delay` - Delay execution
- `POST /session/{sessionId}/control/pause` - Pause session
- `POST /session/{sessionId}/control/stop` - Stop session

#### Events (4 endpoints)
- `POST /session/{sessionId}/event-configs` - Create event configuration
- `GET /session/{sessionId}/event-configs` - Get event configurations
- `GET /session/{sessionId}/events` - Get events
- `POST /session/{sessionId}/events/subscribe` - Subscribe to events
- `GET /session/{sessionId}/events/stream` - Stream events (SSE)

#### Agent (6 endpoints)
- `POST /session/{sessionId}/agent/run` - Run autonomous agent task
- `POST /session/{sessionId}/agent/observe` - Observe page
- `POST /session/{sessionId}/agent/act` - Execute single action
- `POST /session/{sessionId}/agent/extract` - Extract structured data
- `POST /session/{sessionId}/agent/summarize` - Summarize content
- `POST /session/{sessionId}/agent/clearHistory` - Clear agent history

#### Pulsar (4 endpoints)
- `POST /session/{sessionId}/normalize` - Normalize URL
- `POST /session/{sessionId}/open` - Open URL immediately
- `POST /session/{sessionId}/load` - Load URL from storage or internet
- `POST /session/{sessionId}/submit` - Submit URL to crawl pool

### Schemas

Defined 40+ schemas including:
- Request/Response types for all operations
- Error response format
- Element references (WebDriver-compatible)
- Agent-specific types (ObserveResult, ExtractionSchema)
- PulsarSession types (WebPageResult, NormalizeResponse)

### API Characteristics

- **OpenAPI Version**: 3.1.0
- **Total Endpoints**: 41
- **Response Format**: WebDriver-compatible `{"value": ...}` wrapper
- **Error Format**: Structured error responses with error code, message, and stacktrace
- **Server**: Default development server at `http://localhost:8182`

### Compatibility

- WebDriver protocol compatible for standard operations
- Extended with selector-first operations for modern web automation
- AI-powered agent capabilities for autonomous browser control
- PulsarSession integration for advanced crawling scenarios

---

## Version History Summary

| Version | Date | Endpoints | Major Changes |
|---------|------|-----------|---------------|
| 1.0.0 | 2025-01-15 | 41 | Initial stable release |

---

## Future Roadmap

Planned features for upcoming versions:

### v1.1.0 (Planned)
- Enhanced event filtering
- Batch operations
- WebSocket support for real-time updates
- Additional selector strategies

### v2.0.0 (Future)
- URL-based versioning (`/v2/...`)
- Restructured agent operations
- Enhanced error reporting
- Performance optimizations

---

**For detailed API documentation, see:**
- [OpenAPI Specification](./openapi.yaml)
- [API Documentation](./openapi.md)
- [Version Evolution Plan](../docs/api-version-evolution.md)
