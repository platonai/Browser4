# 🚦 WebDriverAgent Developer Guide

## 📋 Prerequisites

Before starting, read the following documents to understand the project structure:

1. **Root Directory** `README-AI.md` - Global development guidelines and project structure
2. All tests that need webpage interaction must use the Mock Server pages located at `pulsar-tests-common/src/main/resources/static/generated/tta`.

## Core Tasks

- Optimize this documentation (✅ updated)
- Implement code according to this specification (✅ implemented: stop() handling, tool list extended)

## 🎯 Overview

[`WebDriverAgent.kt`](WebDriverAgent.kt) is a **multi-round planning executor** that enables AI models to perform
web automation through screenshot observation and historical action analysis. It plans and executes **atomic actions**
(single click, single input, single selection, single wait) step-by-step until the goal is achieved or termination criteria are met.

### Key Architecture Principles

- **Atomic Actions**: Each loop iteration executes exactly one tool call
- **Observation + Memory**: Next action planned from (screenshot + latest history window)
- **Deterministic Interface**: Model must output strict JSON (no markdown / prose)
- **Single-Step Enforcement**: Maximum 1 tool call per response; empty list means "no-op / cannot progress"
- **Graceful Termination**: `stop()` tool, `taskComplete=true`, `method=close`, repeated no-ops, or `maxSteps`
- **Structured Summary**: Final summarization with constrained JSON schema

### Core Workflow

1. System prompt asserts role + constraints + tool schema
2. User message supplies: recent action history (last 8), goal, current URL and implicit screenshot (base64 side channel)
3. Model returns JSON: `{"tool_calls":[{"name":"click","args":{...}}]}` or empty list
4. First (and only) tool call executed; history appended
5. Loop continues until termination condition satisfied
6. Summary model call produces execution recap JSON

### Termination Conditions

Execution loop stops when ANY of these occur:

- Model returns `{"tool_calls":[{"name":"stop","args":{}}]}`
- JSON includes `"taskComplete": true`
- JSON includes `"method":"close"` (driver close attempted)
- Consecutive no-op responses exceed threshold (5)
- Action generation errors exceed threshold (3)
- Step count reaches configured `maxSteps`

## 🔧 Key Components

### TextToAction Integration

```kotlin
// Generate EXACT ONE step using AI
val action = tta.generateWebDriverAction(message, driver, screenshotB64)

// Execute model-produced action list (currently only first is dispatched)
suspend fun act(action: ActionDescription): InstructionResult
```

### Execution Pipeline

1. `safeScreenshot()` → best-effort base64 page snapshot
2. `buildOperatorSystemPrompt(goal)` + dynamic user message
3. LLM call → constrained JSON tool call response
4. Tool dispatch → low-level WebDriver operations
5. Append concise history line (eg: `#5 click -> #login-btn x1`)
6. Check termination & loop control
7. After exit → summarization + transcript persistence

### Supported Tool Calls (Current Contract)

Category | Tools
:--|:--
Navigation | `navigateTo(url)`, `waitForSelector(selector, timeoutMillis)`
Interaction | `click(selector)`, `fill(selector, text)`, `press(selector, key)`
Form | `check(selector)`, `uncheck(selector)`
Scrolling | `scrollDown(count)`, `scrollUp(count)`, `scrollToTop()`, `scrollToBottom()`, `scrollToMiddle(ratio)`, `scrollToScreen(screenNumber)`
Advanced Selection | `clickTextMatches(selector, pattern, count)`, `clickMatches(selector, attrName, pattern, count)`, `clickNthAnchor(n, rootSelector)`
Visual | `captureScreenshot()`, `captureScreenshot(selector)`
Timing | `delay(millis)`
Control | `stop()` (explicit termination)

> NOTE: Only the FIRST tool call in `tool_calls` is executed per step (others are discarded if provided).

### Sample Valid Model Response

```json
{
  "tool_calls": [
    {"name": "click", "args": {"selector": "#search-button"}}
  ]
}
```

Explicit completion example:

```json
{
  "tool_calls": [ {"name": "stop", "args": {}} ],
  "taskComplete": true,
  "method": "close"
}
```

### Error Handling & Resilience

Aspect | Strategy
:--|:--
Malformed JSON | Fallback parser, then treated as no-op
Missing / blank args | Action skipped with descriptive history line
Unknown tool name | Skipped (`skip unknown tool 'xyz'`)
Screenshot failure | Logged; loop continues
Driver action failure | History records `ERR <tool>`; loop continues
Repeated failures | Threshold-based termination (configurable constants inline)

### History Format

Each line is prefixed with step number (or FINAL). Examples:
```
#1 navigateTo -> https://example.com
#2 waitForSelector -> #login-btn (5000ms)
#3 click -> #login-btn x1
FINAL {"taskComplete":true,"summary":"..."}
```

## ♻️ Extensibility & Injection (New)

The agent now supports dependency injection to simplify deterministic unit testing and custom orchestration:

Injection Point | Type | Purpose
:--|:--|:--
`actionGenerator` | `suspend (prompt, driver, screenshot?) -> ActionDescription` | Bypass real LLM; return synthetic JSON/tool calls
`executionStrategy` | `ActionExecutionStrategy` | Customize how a single tool call is executed (e.g. dry-run, logging, batching)
`historySnapshot()` | `List<String>` | Read-only copy of accumulated step history for assertions / diagnostics

### Example: Deterministic Test Harness
```kotlin
val fakeGenerator: suspend (String, WebDriver, String?) -> ActionDescription = { _, _, _ ->
    ActionDescription(emptyList(), null, ModelResponse("""{"tool_calls":[{"name":"stop","args":{}}]}""", ResponseState.SUCCESS))
}
val agent = WebDriverAgent(fakeDriver, actionGenerator = fakeGenerator)
agent.execute(ActionOptions("noop"))
assertTrue(agent.historySnapshot().any { it.contains("stop()") })
```

### Custom Execution Strategy Skeleton
```kotlin
object LoggingExec : ActionExecutionStrategy {
  override suspend fun execute(driver: WebDriver, actionDescription: ActionDescription, toolCallName: String, args: Map<String, Any?>): String? {
    println("Would execute: $toolCallName $args")
    return "dry-run $toolCallName"
  }
}
```

### Disabling LLM for Tests
Set system property to suppress model initialization & external network calls:
```bash
-Dpulsar.tta.disableLLM=true
```
(or inside test code: `System.setProperty("pulsar.tta.disableLLM", "true")`)

This forces `TextToAction.model` to `null`, causing generation helpers to return `LLM_NOT_AVAILABLE` unless an injected `actionGenerator` is provided.

## 📊 Observability

- **History Window**: Last 8 lines passed back for context (keeps prompt small)
- **Transcript Files**: `agent/session-<epoch>.log` contain full trace + final JSON
- **Screenshots**: When explicitly captured, saved as `agent/screenshot-<epoch>.b64`
- **Structured Summary**: JSON containing keys: `taskComplete`, `summary`, `keyFindings`, `nextSuggestions`

## 🔒 Security & Safety

Control | Notes
:--|:--
URL Scheme Filter | Blocks non-http(s) navigation
Selector Use | Must be explicit; no invented selectors
Atomic Semantics | Prevents multi-action hallucinations
Rate Moderation | Minor delay between steps + backoff on no-ops
Stop Conditions | Multiple layers prevent infinite loops

## ✅ Recent Enhancements (This Revision)

- Added support + docs for: `scrollToScreen`, `clickTextMatches`, `clickMatches`, `clickNthAnchor`, `stop()`
- Unified termination logic (explicit stop tool, close method, taskComplete flag)
- Enriched system prompt with termination contract & safety line
- Strengthened documentation (tool taxonomy, examples, observability table)
- Injectible `actionGenerator` & `executionStrategy` for deterministic tests
- Added `pulsar.tta.disableLLM` system property to run offline
- Added transcript truncation safeguard (max 500 lines / 500 chars per line)

## 🧪 Testing Guidance

Recommended focused unit tests:

- Tool call parsing (`parseToolCalls`) covers numeric + string args
- Mapping coverage for newly added advanced click & scroll tools
- Termination logic via synthetic model responses (mock model returning stop / no-op)
- Summary JSON schema shape validation (keys present, no extraneous fields)
- Execution strategy dry-run verification

> Existing lightweight parsing tests live under `src/test/.../ai/tta`.

## 🔄 Future Improvement Ideas

- Pluggable retry / recovery strategies (eg. re-locate element variants)
- Adaptive wait heuristics before acting after navigation
- Multi-tool suggestion mode with internal action ranking
- DOM diff compression instead of screenshot for certain steps
- Telemetry hooks (metrics counters for each tool call / termination path)

## Examples

See: [`SessionInstructionsExample`](/pulsar-examples/src/main/kotlin/ai/platon/pulsar/examples/agent/SessionInstructions.kt)

---
**End of Developer Guide**
