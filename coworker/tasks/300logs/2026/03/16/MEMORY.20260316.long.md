● Task: Compress content

# Daily Memory - 2026-03-16

## 1. Unified Tool Naming (Frontend/Backend)
**Context:** CLI wrapper previously aliased tool names.
**Change:** Removed aliasing from `sdks/browser4-cli`. Updated `browser4-rest` `MCPToolController` to accept `browser_*` names directly.
**Validation:** CLI `npm test`, Backend `MCPToolControllerTest`, and `browser4-rest-tests` passed.
**Key Insight:** API compatibility layers belong at the backend boundary.
**Testing Strategy:** `mvn install` changed modules locally before running `pulsar-tests` with `-DrunE2ETests=true`.

## 2. Log Encoding Fix
**Context:** Windows default charset caused garbled localized logs.
**Change:** Added `<charset>UTF-8</charset>` to all file appenders in `pulsar-core` logback configs.
**Validation:** Verified `pulsar.log` encodes UTF-8 correctly despite JVM defaults.
**Key Insight:** Explicit charset configuration is required for robust localization; never rely on JVM defaults.

## 3. Real CLI E2E Coverage
**Context:** Unit tests missed CLI/Backend contract mismatches.
**Change:** Added `npm run test:e2e` in `sdks/browser4-cli` running live `Browser4.jar`. Fixed `eval`, `upload`, `type`, `press` contracts.
**Validation:** `npm run test:e2e` passed; guard ensures alignment with `supportedCommandsArray`.
**Key Insight:** Live E2E tests are essential for detecting argument shape/identifier mismatches and covering known gaps (e.g., aliases).

## 4. DOMState Aria Snapshot Fallback
**Context:** `DOMStateBuilderTest` failed; `ariaSnapshot` was empty when `optimizedDOMTree` was null.
**Change:** Updated `DOMState.ariaSnapshot` in `DomModels.kt` to fallback to `serializableTree.toNanoTreeUnfiltered().ariaSnapshot` if needed.
**Validation:** `DOMStateBuilderTest` passed (16 tests).
**Key Insight:** `DOMState` requires fallback logic for scenarios (like tests) where `OptimizedDOMTree` is unavailable.

## 5. PulsarWebDriver Selector Conversion
**Context:** `waitForScrollSettled` failed with `e123` selectors as `document.querySelector` rejects them.
**Change:** Implemented `convertSelectorIfNecessary` in `PulsarWebDriver.kt` to resolve `e123` to CSS via `SnapshotService`.
**Validation:** Compiled `pulsar-protocol`.
**Key Insight:** Methods injecting raw JS must manually handle `e123` -> CSS conversion, bypassing `PageHandler` logic.



## 6. WebDriver waitForFunction Implementation
**Context:** `WebDriver.waitForFunction` was defined in the interface but missing in `PulsarWebDriver` and `PlaywrightDriver`.
**Change:**
- Implemented `waitForFunction` in `PulsarWebDriver.kt` using `evaluate` to check for truthy values.
- Implemented `waitForFunction` in `PlaywrightDriver.kt` using `page.waitForFunction`.
- Removed `override` from `clickNthAnchor` in `PlaywrightDriver.kt` as it's no longer in the interface.
- Replaced unresolved `normalizeCSSSelector` with `page.convertSelectorIfNecessary` in `PulsarWebDriver.waitForScrollSettled`.
**Validation:** Compiled `pulsar-protocol` and `pulsar-protocol-playwright` successfully.
**Key Insight:** When modifying `WebDriver` interface or implementations, ensure both `PulsarWebDriver` (Chrome CDP) and `PlaywrightDriver` are updated to maintain build integrity, even if Playwright is test-only.
