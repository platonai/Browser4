# Browser4 Agent Tools

## Introduction

High-level agent tools built on Browser4 core functionality (`browser4-core` module).
This module provides advanced agentic capabilities for web scraping, crawling, and
stateful page interaction — including AI-powered hyperlink extraction, DOM utilities,
REST API prompt helpers, and stateful agent/visitor runners.

## Module

- **Artifact:** `ai.platon.pulsar:browser4-agent-tools`
- **Parent:** `ai.platon.pulsar:browser4` (version 4.12.x)
- **Language:** Kotlin
- **Build:** Maven

## Dependencies

| Dependency | Description |
|------------|-------------|
| `browser4-skeleton` | Core skeleton and abstractions |
| `browser4-parse` | HTML/DOM parsing utilities |
| `browser4-agentic` | Base agentic framework |
| `pulsar-ql` | Pulsar query language (XSQL) support |
| `pulsar-dom` | DOM manipulation extensions |
| `caffeine` | High-performance caching |
| `spring-boot-test` | Test support (test scope) |

## Package Structure

### `agent/`
Stateful agent execution with persistence and event tracking:

- **`StatefulAgentRunner`** — Runs agents with persistent state across sessions. Features a size-bounded Caffeine cache (100 entries, 2h TTL), JSONL-backed persistence so task statuses survive server restarts, periodic compaction of expired entries, and automatic save/restore of the user's page URL to avoid polluting the shared browser session.
- **`Models`** (`AgentTaskStatus`) — Data model for agent task state, including lifecycle timestamps (`createdTime`, `startedTime`, `finishTime`), agent state history, SSE event handlers, submitted task tracking, and failure reason.

### `common/`
Cross-cutting persistence utilities shared by agent, crawl, and swarm components:

- **`JsonlPersistence`** — Generic JSONL (JSON Lines) persistence helper. Entries are serialised as single-line JSON and appended to a file. On startup, the file is replayed to restore in-memory state. Supports clear for bulk operations. Used by `StatefulAgentRunner`, `SwarmService`, and `CrawlService`.

### `crawl/common/`
Shared scraping and crawling utilities:

- **`AbstractScrapeHyperlink`** — Base class for AI-driven hyperlink scraping.
- **`XSQLScrapeHyperlink`** / **`DegenerateXSQLScrapeHyperlink`** — XSQL-based hyperlink extraction.
- **`APISQLUtils`** / **`ScrapeAPIUtils`** — Utilities for REST API and SQL-backed scraping.
- **`RestAPIPromptUtils`** / **`Prompts`** — Prompt construction helpers for REST API and LLM interactions.
- **`DomUtils`** — DOM manipulation and traversal utilities.
- **`NormXSQL`** — XSQL normalization and transformation.

### `crawl/service/`
- **`ScrapeService`** — Service layer for coordinated scraping operations.

### `crawl/`
- **`StatefulPageVisitor`** — Visits pages maintaining state, useful for authenticated or multi-step crawls.
- **`Models`** — Crawl-specific data models: `ScrapeResponse` and `PageVisitStatus` (with lifecycle timestamps — `createdTime`, `startedTime`, `finishTime`), `PageVisitRequest` (with optional `inferUriExtractionRegex`), `PageVisitResult`, and `PGInstructResult`.

## Subdirectories

| Directory | Description |
|-----------|-------------|
| `src/` | Main and test source code (Kotlin) |
| `logs/` | Runtime log output (`pulsar.*.log` files) |
| `target/` | Maven build output (git-ignored) |

## Test Resources

The `src/test/resources/` directory includes:

- **`html/`** — Sample HTML pages for parser testing.
- **`metatags/`** — HTML files with various `<meta>` tag configurations.
- **`selector/`** — Page samples paired with extraction rule sets (`rules.txt`).
- **`tika/`** — Apache Tika test documents (PDF, DOC, RTF, RSS, ODT, etc.).
- **`test-context/`** — Spring test context configuration (`parse-beans.xml`).

## Tests

Key test classes in `src/test/kotlin/`:

| Test Class | Description |
|------------|-------------|
| `AgentRunnerPersistenceTest` | Verifies JSONL persistence and restore of agent task statuses |
| `AgentTaskStatusTest` | Tests agent task lifecycle: creation, state transitions, timestamps |
| `ScrapeResponseTest` | Tests scrape response lifecycle, status codes, and timestamp tracking |
| `TestHtmlParser` | HTML parser integration tests against sample fixtures |
| `HtmlParserTestBase` | Shared base class for HTML parser test setup |
