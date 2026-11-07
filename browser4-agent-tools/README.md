# Browser4 Agent Tools

## Introduction

High-level agent tools built on Browser4 core functionality (`browser4-core` module).
This module provides advanced agentic capabilities for web scraping, crawling, and
stateful page interaction — including AI-powered hyperlink extraction, DOM utilities,
REST API prompt helpers, stateful agent/visitor runners, a multi-format page
capture pipeline, and the swarm backend SPI.

## Module

- **Artifact:** `ai.platon.pulsar:browser4-agent-tools`
- **Parent:** `ai.platon.pulsar:browser4` (version 4.16.1-SNAPSHOT, the 4.16.x line)
- **Language:** Kotlin
- **Build:** Maven
- **Java:** 25+

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
| `mockk` / `mockk-jvm` | Kotlin-friendly mocking (test scope) |
| `mockito-kotlin` | JDBC/X-SQL stubbing in tests (test scope) |

## Package Structure

### `agent/`
Stateful agent execution with persistence, live progress events, resumable runs,
and page-status reporting:

- **`StatefulAgentRunner`** — Runs agents with persistent state across sessions.
  Features a size-bounded Caffeine cache (1,000 entries, 2h write TTL refreshed by
  progress events), JSONL-backed persistence so task statuses survive server
  restarts, periodic compaction of expired entries, serialized per-session task
  execution, cancellation, steering-message injection, bounded per-task event
  logs and aggregate run stats, and automatic save/restore of the user's page URL
  to avoid polluting the shared browser session.
- **`Models`** (`AgentTaskStatus`) — Data model for agent task state, including
  lifecycle timestamps (`createdTime`, `startedTime`, `finishTime`), `processState`,
  the observed page HTTP status, tool-call progress, agent state history, SSE event
  handlers, submitted task tracking, workspace/deliverables, coding/access metadata,
  machine-readable failure codes, resume flags, and failure reason.
- **`AgentTaskEvents`** — `AgentTaskEvent` and `AgentTaskRunStats` plus the bounded,
  thread-safe `AgentTaskEventLog` (1,000-event ring buffer with sequence cursors)
  and `AgentTaskEventSanitizer`, which truncates/redacts previews and repairs
  unpaired surrogates. Backs `GET /api/commands/{id}/events` and `/stats`.
- **`AgentResume`** — Builds resume checkpoints and `## Resume context` instructions,
  isolates per-task workspaces, and attributes the deliverables an aborted run
  already produced. Used by `agent run --resume <id>`.
- **`DocumentHttpStatus`** — Reads the loaded document's real HTTP status from
  `performance.getEntriesByType('navigation')[0].responseStatus`; returns "not
  observed" instead of guessing when the page cannot report it.

### `common/`
Cross-cutting persistence utilities shared by agent, crawl, and swarm components:

- **`JsonlPersistence`** — Generic JSONL (JSON Lines) persistence helper. Entries are
  serialised as single-line JSON and appended to a file. On startup, the file is
  replayed to restore in-memory state. Appends are synchronized and rewrites are
  atomic (built off to the side and moved into place), so compaction cannot corrupt
  a task history. Used by `StatefulAgentRunner`, `SwarmService`, and `CrawlService`.

### `crawl/`
- **`StatefulPageVisitor`** — Visits pages maintaining state, useful for authenticated
  or multi-step crawls. Produces page summaries, extracted fields/links, and X-SQL
  results, with a size-bounded (10,000 entries, 2h TTL) status cache.
- **`Models`** — Crawl-specific data models: `ScrapeRequest` / `QueryRequest` (with
  optional `batchId`), `ScrapeResponse` (task and page status, lifecycle timestamps,
  processing/queue/elapsed durations, batch id), `PageVisitRequest` (with optional
  `inferUriExtractionRegex`), `PageVisitResult`, `PGInstructResult`, and
  `PageVisitStatus`.
- **`ScrapeStatusRequest`** — Request wrapper for looking up a previously submitted
  scrape task by id without depending on the REST module.
- **`SwarmFacade`** — SPI for the swarm task backend: submit (single or batched),
  query submission, batch status, task status, counts, and aborting pending tasks.
  The implementation lives in the `browser4-swarm` plugin; the REST controller
  delegates to it.
- **`SwarmFacadeMount`** / **`SwarmFacadeRegistry`** — Plugin mount point and
  runtime registry that let `PluginManager` discover and register/replace the
  active swarm facade.
- **`SwarmSessionProvider`** — Supplies the shared swarm `GenericAgenticSession` to
  the backend implementation without a REST-module dependency.

### `crawl/common/`
Shared scraping and crawling utilities:

- **`ScrapeHyperlink`** / **`AbstractScrapeHyperlink`** — Completable hyperlink and
  base class for submitted scrape tasks.
- **`XSQLHyperlink`** / **`DegenerateXSQLScrapeHyperlink`** — X-SQL-based hyperlink
  extraction and its non-X-SQL fallback.
- **`ScrapeHyperlinkFactory`** — Creates the right hyperlink type for a request and
  registers the shared `onLoaded` callback.
- **`APISQLUtils`** / **`ScrapeAPIUtils`** — Utilities for REST API and SQL-backed
  scraping, including X-SQL normalization and read-only sealing.
- **`XSqlExecutor`** / **`XSqlResult`** — The single place an X-SQL statement is
  executed on the session's SQL context: deterministic column order and
  status-mapped failures instead of exceptions.
- **`RestAPIPromptUtils`** / **`Prompts`** — Prompt construction helpers and
  placeholder templates for REST API and LLM interactions.
- **`DomUtils`** — DOM manipulation and traversal utilities.
- **`NormXSQL`** / **`DegenerateXSQL`** — Normalized X-SQL representations.

### `crawl/service/`
- **`ScrapeService`** — Service layer for coordinated scraping: loads and parses
  documents, applies page actions, executes query tasks, and caches responses.

### `format/`
Firecrawl-style document format pipeline, including Browser4 extensions such as
`readability`, `deterministicJson`, and `pdf`:

- **`PageFormatEngine`** — Executes a `PageFormatPlan`: captures the page once, runs
  every snapshot-scoped read before any live-tab step, assembles the requested
  fields, applies `strict`/required failure semantics, and invokes plugin format
  contributors against the same capture.
- **`PageFormatPlan`** / **`PageFormatPlanBuilder`** — The compiled plan (`FormatStage`,
  `StepPolicy`, `FormatStep`, `ArtifactSpec`) and the pure builder that merges
  duplicate steps, orders stages, and records degradations before anything runs.
- **`FormatProvider`** / **`FormatProviders`** — Provider SPI and registry for
  `markdown`, `html`, `rawHtml`, `readability`, `links`, `images`, `attributes`,
  `deterministicJson`, `screenshot`, and `pdf`.
- **`FormatStepRunner`** / **`FormatSnapshot`** — Host SPI for the single capture,
  snapshot reads (`html_snapshot.*`), live-tab calls (`tab.*`), method availability,
  and binary artifact persistence (screenshot/PDF paths).
- **`FormatFailure`** — `FormatDegradation` and `FormatFailureException`, carrying a
  stable `ToolErrorCode` so `strict` failures report 400/502/503/504 consistently.
- **`providers/`** — Document, extraction, and live-tab format providers.

## Subdirectories

| Directory | Description |
|-----------|-------------|
| `src/main/kotlin/` | Main source code (Kotlin) |
| `src/test/kotlin/` | Test source code (Kotlin) |
| `src/test/resources/` | Test fixtures and Spring test context |
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
| `AgentResumeTest` | Resume checkpoints, workspace/deliverable attribution, and resume instructions |
| `AgentTaskEventLogTest` | Bounded event ring buffer, sequence cursors, and stats after eviction |
| `AgentTaskEventSanitizerTest` | Preview truncation, credential redaction, and surrogate repair |
| `AgentTaskStatusTest` | Agent task lifecycle: creation, state transitions, timestamps, failure codes |
| `DocumentHttpStatusTest` | Navigation-timing HTTP status probe, timeouts, and "not observed" cases |
| `StatefulAgentRunnerTest` | Serialized execution, cancellation, steering, persistence, and resume paths |
| `TaskScopedHistoryTest` | Keeps only the agent states produced by the current task |
| `ScrapeResponseTest` | Scrape response lifecycle, status codes, queue/processing durations, timestamps |
| `TolerantFieldExtractorTest` | Tolerant extraction of fields from imperfect LLM/text output |
| `ScrapeAPIUtilsTest` | X-SQL normalization and read-only query sealing |
| `XSqlExecutorTest` | Row ordering, error/status mapping, and context validation |
| `XSQLScrapeHyperlinkTest` | X-SQL hyperlink extraction and result assembly |
| `PageFormatEngineTest` | Capture-once, stage isolation, degradation, contributors, and strict failures |
| `PageFormatPlanBuilderTest` | Plan ordering, step deduplication, policy upgrade, and degradations |
| `PdfFormatProviderTest` | PDF artifact path/base64 output and refused options |
| `ScreenshotFormatProviderTest` | Screenshot artifact output, full-page/base64, and refused options |
| `TestHtmlParser` | HTML parser integration tests against sample fixtures |
| `HtmlParserTestBase` | Shared base class for HTML parser test setup |

## Build and Test

```bash
# Compile the module and its dependencies, then run its tests
./mvnw -pl browser4-agent-tools -am test

# Run a single test class
./mvnw -pl browser4-agent-tools -am -Dtest=PageFormatEngineTest test
```

On Windows PowerShell, use `.\mvnw.cmd` with quoted `-D` arguments:

```powershell
.\mvnw.cmd -pl browser4-agent-tools -am "-Dtest=PageFormatEngineTest" test
```

## Related Documentation

- [Firecrawl-compatible formats design](../docs-dev/copilot/firecrawl-compatible-formats-design.md)
- [Agent run flow](../docs-dev/copilot/agent-run-flow.md)
- [Testing taxonomy](../docs/TESTING.md)

*Last updated: 2026-11-03*
