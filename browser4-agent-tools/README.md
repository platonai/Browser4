# Browser4 Agent Tools

## Introduction

High-level agent tools built on Browser4 core functionality (`browser4-core` module).
This module provides advanced agentic capabilities for web scraping, crawling, and
stateful page interaction — including AI-powered hyperlink extraction, DOM utilities,
REST API prompt helpers, and stateful agent/visitor runners.

## Module

- **Artifact:** `ai.platon.pulsar:browser4-agent-tools`
- **Parent:** `ai.platon.pulsar:browser4` (version 4.11.x)
- **Language:** Kotlin
- **Build:** Maven

## Dependencies

| Dependency | Description |
|------------|-------------|
| `browser4-skeleton` | Core skeleton and abstractions |
| `browser4-parse` | HTML/DOM parsing utilities |
| `browser4-agentic` | Base agentic framework |
| `pulsar-ql` | Pulsar query language (XSQL) support |
| `caffeine` | High-performance caching |
| `spring-boot-test` | Test support (test scope) |

## Package Structure

### `agent/`
Stateful agent execution:

- **`StatefulAgentRunner`** — Runs agents with persistent state across sessions.
- **`Models`** — Data models for agent configuration and state.

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
- **`Models`** — Crawl-specific data models.

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
