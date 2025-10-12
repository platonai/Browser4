# Evaluate API Optimization Plan (Playwright-Aligned)

This document analyzes how to optimize the evaluate-series functions and proposes an ideal, Playwright-like interface and behavior.

## Checklist (for this phase)
- Analyze current API and behavior gaps.
- Define ideal semantics referencing Playwright.
- Propose a concrete API surface and options.
- Specify serialization, argument, error, frame/world semantics.
- Outline backend mapping (Playwright, CDP) and testing plan.
- Define backward compatibility and rollout plan.

---

## 1) Scope and Objectives
- Make evaluate ergonomic and predictable without requiring IIFE wrappers.
- Reach functional parity with Playwright for common flows: value vs handle, args passing, selector/element context, auto-await promises, robust error semantics.
- Unify behavior across backends (Playwright and CDP) with a single clear contract.
- Preserve backward compatibility while providing a straightforward migration path.

Success criteria
- Developers can pass args, return JSON-serializable data, or receive handles consistently.
- Element/selector-based evaluate is available and reliable.
- Timeouts and navigation/detach conditions produce consistent, actionable exceptions.
- Tests covering edge cases pass uniformly across backends.

## 2) Current State Summary (today)
Public API (selected):
- `suspend fun evaluate(expression: String): Any?`
- `suspend fun <T> evaluate(expression: String, defaultValue: T): T`
- Beta: `evaluateDetail`, `evaluateValue`, `evaluateValueDetail`

Observed behavior/pain points:
- Docs suggest using IIFE to return values; inconvenient vs Playwright’s auto function handling.
- No argument passing; string concatenation is error-prone.
- No element/selector-scoped evaluate; must embed selection logic in strings.
- Ambiguous value vs handle semantics; overlapping evaluate* families.
- Limited error/timeout framing and no unified exception taxonomy.

## 3) Design Principles
- Playwright parity where sensible; surprises minimized.
- Clear separation of “value” (JSON-serializable) vs “handle” (remote references).
- Backward compatible by default; additive improvements first, deprecate gradually.
- Deterministic, documented serialization rules and stable error shapes.

## 4) Proposed API Surface

### 4.1 Page-level
- evaluate(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): Any?
  - Accepts expression OR function body (Playwright-style). Auto-await promises. Returns JSON-serializable value (undefined -> null).
- evaluateJson<T>(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): T
  - Returns typed JSON decoded to T (throws on decode mismatch).
- evaluateHandle(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): JsHandle
  - Returns a handle to non-serializable results (DOM nodes, functions…). Caller must dispose.
- waitForFunction(expressionOrFunction: String, options: WaitForFunctionOptions = ..., vararg args: Any?): Any?
  - Polls until returns truthy; returns last truthy JSON-serializable value.

### 4.2 Selector convenience (single/all)
- evaluateOnSelector(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): Any?
- evaluateOnSelectorJson<T>(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): T
- evaluateOnSelectorAll(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): Any?
  - Function receives an array of elements.
- evaluateOnSelectorHandle(selector: String, expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): JsHandle

### 4.3 Element-based (with ElementHandle)
- element.evaluate(expressionOrFunction: String, vararg args: Any?, options: EvaluateOptions = ...): Any?
- element.evaluateJson<T>(...): T
- element.evaluateHandle(...): JsHandle

### 4.4 Types and options
- data class EvaluateOptions(
  - timeout: Duration? = null,
  - awaitPromise: Boolean = true,
  - world: String? = null, // null => main world
  - frameTarget: FrameTarget = FrameTarget.CurrentMain,
  - returnByValue: Boolean = true // internal alias of serialization mode
)
- data class WaitForFunctionOptions(
  - timeout: Duration? = null,
  - polling: Polling = Polling.RAF // or interval(ms)
)
- sealed interface Polling { object RAF; data class Interval(val ms: Long) }
- interface JsHandle { fun dispose(); val type: String; /* optional: preview, id */ }
- interface ElementHandle : JsHandle
- data class JsEvaluation(
  - value: Any?,
  - type: String,
  - handleId: String? = null,
  - durationMs: Long? = null,
  - exception: JsError? = null
)
- data class JsError(message: String, stack: String?)
- sealed class WebDriverException …
  - EvaluationTimeoutException, EvaluationNavigationAbortedException,
  - EvaluationSerializationException, EvaluationScriptException,
  - FrameDetachedException, ElementDetachedException

## 5) Semantics

### 5.1 Expression vs Function forms
- Accept either:
  - Expression string: `"document.title"` or `"el.textContent"` (in element/selector contexts)
  - Function body: `"() => document.title"`, `"(a,b) => a+b"`, `"el => el.textContent"`
- Auto-await promises; returned promise values become the method’s return value.
- Undefined maps to null.

### 5.2 Argument passing
- Supported arg types: JSON-serializable primitives, lists, maps, data classes; JsHandle/ElementHandle (by reference).
- For selector/element evaluate, the selected element is bound as the first parameter or as `this` for function forms (prefer Playwright style: element passed as first arg to function, or bound as `this` when using element.evaluate).

### 5.3 Return value mapping (JSON-serializable subset)
- JS -> Kotlin mapping:
  - undefined -> null; null -> null
  - boolean -> Boolean
  - number -> Double (documented), optionally Long if safe; BigInt -> String or BigInteger (decision below)
  - string -> String
  - array -> List<Any?>
  - plain object -> Map<String, Any?>
  - Date -> ISO string
  - RegExp/Function/Symbol/DOM nodes -> not serializable; require evaluateHandle
- `evaluateJson<T>` decodes to T using a consistent JSON library (kotlinx.serialization preferred); throws on mismatch.

### 5.4 Error semantics
- Timeouts: respect `options.timeout` or driver default; throw `EvaluationTimeoutException` including timeout and expression summary.
- Navigation/frame detach during evaluation: abort and throw `EvaluationNavigationAbortedException` or `FrameDetachedException`.
- JS exceptions: throw `EvaluationScriptException` with message and stack; include truncated code snippet and arg summary.
- Serialization issues: throw `EvaluationSerializationException` with guidance to use handles.
- Normalize errors across backends to the above taxonomy.

### 5.5 Frames and worlds
- Default to current page’s main world.
- `options.world` allows alternate execution world when supported.
- `options.frameTarget` is future-proofing; today targets current main frame (or element’s frame for element evaluate).

## 6) Backend Mapping

### 6.1 Playwright backend
- Page evaluate/handle: map to `page.evaluate` / `page.evaluateHandle` with args.
- Selector variants: `page.querySelector(selector)?.evaluate(fn, args)`; for all: `page.querySelectorAll(selector)` then pass array.
- Element handle types: use Playwright’s ElementHandle under the hood; wrap in our `ElementHandle` interface.
- WaitForFunction: map to Playwright’s `page.waitForFunction` semantics.
- World: default main world; plan future isolated world mapping if needed.

### 6.2 CDP backend (Chrome DevTools)
- Value evaluate: use `Runtime.evaluate` (expression) and `Runtime.callFunctionOn` (function with args), with `returnByValue=true`.
- Handle evaluate: same but `returnByValue=false`, materialize a `JsHandle` with remote object id; implement `dispose` via `Runtime.releaseObject`.
- Selector evaluate: resolve node to object id via `DOM.resolveNode` then `Runtime.callFunctionOn` with that handle as receiver/arg.
- Element array (selectorAll): use `querySelectorAll` in page script to build array and pass to function.
- Promise await: set `awaitPromise=true` in CDP.
- Error normalization: map CDP exception details to `EvaluationScriptException` and friends.

## 7) Backward Compatibility & Migration
- Keep `WebDriver.evaluate(String): Any?` but change behavior to:
  - No IIFE required; auto-await promises; accept function or expression.
  - Return JSON-serializable values; undefined -> null.
- Mark `evaluateValue*` as deprecated in favor of `evaluate` and `evaluateJson<T>`.
- Keep `evaluateDetail*` as Beta for telemetry with normalized `JsEvaluation`.
- Provide migration notes and examples; add deprecation warnings in release notes.

## 8) Testing Strategy
Create cross-backend tests:
- Primitives and complex objects serialization.
- Promise resolution and async function return.
- Argument passing (primitives, objects, large payloads).
- Selector and element evaluate; detached element error behavior.
- Navigation during evaluation -> aborted exception.
- Timeouts -> timeout exception.
- Non-serializable returns -> serialization exception; evaluateHandle returns non-null handle; dispose works.
- BigInt handling according to chosen mapping.
- Frames/world (basic): evaluate in nested frame via element.

## 9) Telemetry & Logging
- Log evaluation id, backend, elapsed time, result type (value/handle), size (truncated), and whether obfuscation was applied.
- Redact/minify logged code per settings; include first/last N chars only.

## 10) Rollout Plan
- Phase 1 (behind feature flag): implement new evaluate semantics and selector variants; keep old methods.
- Phase 2 (default): switch `evaluate(String)` to new semantics; add deprecation of evaluateValue*.
- Phase 3 (cleanup): remove deprecated APIs in a major release after migration window.

## 11) Examples Cheat Sheet
- `driver.evaluate("() => document.title")`
- `driver.evaluate("(a,b) => a+b", 1, 2)` -> `3`
- `driver.evaluateJson<MyDto>("() => ({a:1,b:'x'})")`
- `driver.evaluateOnSelector("h1", "el => el.textContent")`
- `val h = driver.evaluateHandle("() => document.body"); h.dispose()`
- `driver.waitForFunction("() => window.appReady === true", WaitForFunctionOptions(timeout = 30.seconds))`

## 12) Open Questions / Decisions
- BigInt mapping: String vs BigInteger (suggest BigInteger on JVM if feasible; else String, documented).
- Default JSON library for evaluateJson<T>: kotlinx.serialization (preferred) vs Jackson; pick one and standardize.
- World selection: keep main world default, plan for isolated world option later.

