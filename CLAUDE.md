# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Browser4 is a Kotlin/Java-based web automation and data extraction platform built with Spring Boot. It provides AI-powered web scraping, browser automation with LLM integration, and an X-SQL query language for web data extraction.

## Common Development Commands

### Building and Testing
```bash
# Build without tests (development)
./bin/build.sh

# Build with tests
./bin/build.sh -test

# Build specific module
./mvnw clean install -pl pulsar-core

# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=ChatModelFactoryTest

# Run tests with coverage
./mvnw test -Pci
```

### Running the Application
```bash
# Start with Docker Compose (includes MongoDB)
docker-compose up -d

# Start with proxy service
docker-compose --profile proxy up -d

# Run directly
./bin/browser4.sh
```

### Code Quality
```bash
# Run OWASP dependency check
./mvnw dependency-check:check

# Generate coverage report
./mvnw verify -Pci
```

## High-Level Architecture

### Module Structure
- **pulsar-core/** - Core functionality with submodules (common, dom, persist, plugins, ql, resources, skeleton, spring-support)
- **pulsar-browser4/** - Main Spring Boot application entry point
- **pulsar-rest/** - REST API implementation
- **pulsar-third/pulsar-llm/** - LLM integration using LangChain4j
- **pulsar-tools/pulsar-auto-coder/** - AI-powered test code generation
- **pulsar-ql/** - X-SQL query language implementation

### Key Architectural Patterns

**PulsarSession** is the central API interface providing all web automation capabilities:
- Load/parse operations: `load()`, `parse()`, `loadDocument()`
- Scrape operations: `scrape()`, `scrapeOutPages()`
- LLM integration: `chat()` methods for pages, documents, elements
- Export operations: `export()`, `persist()`

**Coroutine-Based Architecture**:
- All browser operations use suspend functions for async/await patterns
- WebDriver interface provides comprehensive browser control with suspend functions
- Integration with CompletableFuture for Java compatibility

**X-SQL Query Language**:
- Extended SQL built on H2 database with custom UDFs
- DOM functions: `load()`, `fetch()`, `select()`, DOM property accessors
- LLM functions: `llm_extract()`, `llm_chat()`, `llm_classify()`
- Enables SQL-like queries against web content

**Plugin Architecture**:
- Protocol layer: Browser emulation and fetching
- Parse layer: HTML parsing and content extraction
- Filter layer: URL filtering and normalization
- Schedule layer: Task scheduling and management

**Event-Driven Design**:
- Page lifecycle events: `willNavigate`, `navigated`, `documentFullyLoaded`
- Interaction events: `willInteract`, `didInteract`, `willScroll`, `didScroll`
- EventEmitter interface for reactive programming

### Configuration System
- **ImmutableConfig**: Startup configuration (never changes)
- **VolatileConfig**: Runtime configuration (can be modified)
- **LoadOptions**: Per-request configuration with argument parsing
- Profile-based configuration support (dev, ci, release, deploy)

### Testing Conventions
- Test classes use "AITest" suffix (e.g., `DataCollectorsAITest`)
- JUnit 5 with Kotlin test assertions
- Mockito with SpringMockK for Kotlin
- Excluded test groups: `TimeConsumingTest`, `ExternalServiceTest`

### LLM Integration Points
- **ChatModel interface**: Unified interface for different LLM providers
- **PulsarSession**: Direct chat methods for content analysis
- **WebDriver**: `chat()` and `instruct()` methods for browser automation
- **X-SQL**: LLM functions in SQL queries
- **REST API**: Conversation management endpoints

### Key Interfaces to Understand
- `BrowserEmulator`: Core browser automation interface
- `WebDriver`: Comprehensive browser control interface
- `ChatModel`: LLM integration interface
- `PulsarSession`: Main user-facing API
- `EventEmitter`: Reactive event handling

This architecture combines traditional web scraping with AI-powered content analysis, built on a reactive coroutine foundation for high performance and scalability.