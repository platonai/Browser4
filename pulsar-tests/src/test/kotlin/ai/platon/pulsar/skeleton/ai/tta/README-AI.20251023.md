# 🚦 AI Coder Agent Guideline for Text-To-Action Testing

This document guides AI agents on how to create, run, and evolve tests for the Text-To-Action (TTA) feature.

## 📋 Prerequisites

Before you start, read the following to understand the project landscape:

1. Root `README-AI.md` – global dev rules and project structure
2. Core guide `pulsar-core/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/ai/README-AI.md` – TTA core implementation
3. All tests must use the Mock Server pages under `pulsar-tests-common/src/main/resources/static/generated/tta`

## 🎯 Test Goals

Test the Text-To-Action (TTA) capability to ensure the AI correctly translates natural language commands into executable WebDriver actions.

Focus areas:
- `ai.platon.pulsar.skeleton.ai.tta.TextToAction#generateWebDriverAction`
- Only test generateWebDriverAction; ignore other methods
- Natural language → WebDriver API mapping accuracy
- Reliable identification and selection of interactive elements
- Stability of element references under DOM changes

## 🛠 Test Environment

### Directory Layout
```
pulsar-tests/src/test/kotlin/ai/platon/pulsar/skeleton/ai/tta/    # Test code directory
├── TextToActionTestBase.kt                                       # Test base class
├── TextToActionBasicTest.kt                                      # Basic sanity tests
├── TextToActionSimpleTest.kt                                     # Simple scenarios
├── TextToActionTest.kt                                           # General functionality tests
├── TextToActionGenerateWebDriverActionTest.kt                    # generateWebDriverAction focus
├── TextToActionElementSelectionTests.kt                          # Element selection tests
├── TextToActionElementInteractionTests.kt                        # Element interaction tests
├── TextToActionEdgeCasesTest.kt                                  # Edge cases
├── TextToActionComprehensiveTests.kt                             # Comprehensive tests
├── TextToActionMockServerTests.kt                                # Mock server wiring tests
├── NewToolsIntegrationTest.kt                                    # New tools integration tests
├── ConditionalActionsAndNavigationTest.kt                        # Conditional actions and navigation tests
└── README-AI.md                                                  # This file

# Note: Interactive test pages live in a shared module used by multiple suites
pulsar-tests-common/src/main/resources/static/generated/tta       # Actual test web page directory
├── interactive-1.html                                            # Basic interactions
├── interactive-2.html                                            # Complex forms
├── interactive-3.html                                            # Animation/basic dynamics
├── interactive-4.html                                            # Dark mode + drag-and-drop
├── interactive-dynamic.html                                      # Async/dynamic content
├── interactive-ambiguity.html                                    # Ambiguity/resolution scenarios
├── forms-advanced-test.html                                      # Advanced form controls
└── interactive-screens.html                                      # Multi-screen placeholder (to be enhanced)
```

### Environment Requirements
- Java version: derive from root `pom.xml`
- Build tool: use Maven Wrapper from project root (Windows: `mvnw.cmd`, Linux/macOS: `./mvnw`)
- LLM config: requires API keys via environment/properties
- Web server: inherited by `WebDriverTestBase` (auto-starts Mock Server)
- WebDriver: use `runWebDriverTest` provided by `WebDriverTestBase`

## 🔧 Test Foundation

### Inheritance
```text
WebDriverTestBase              # Provides mock web server and WebDriver support (@SpringBootTest included)
    ↓
TextToActionTestBase          # TTA-specific fixtures (@SpringBootTest + LLM checks included)
    ↓
Concrete tests                # Actual test classes
```

Note: Test classes do not need to add `@SpringBootTest` again; the base already includes it.

### LLM Configuration Check
The base test class automatically checks for LLM configuration:
- If API keys are missing, tests are skipped with a configuration hint
- Configuration files may be resolved from:
    - `${project.baseDir}/application[-private].properties`
    - `AppPaths.CONFIG_ENABLED_DIR/application[-private].properties`
- Environment variables are supported

## 📝 Test Authoring Guidelines

### 1. File Naming
- Functional tests: `<Feature>Test.kt` (e.g., `TextToActionTest.kt`)
- Comprehensive suites: `<Feature>ComprehensiveTests.kt`
- Integration tests: `<Feature>IT.kt`

### 2. Method Naming
Use descriptive backtick names in BDD style:
```kotlin
@Test
fun `When ask to click a button then generate correct WebDriver action code`() = runWebDriverTest { driver ->
    // test implementation
}

@Test
fun `Given complex form when asking to fill a specific field then select correct element`() = runWebDriverTest { driver ->
    // test implementation
}
```

### 3. Annotations
```kotlin
@Tag("ExternalResourceDependent")    // Requires external service (LLM API)
@Tag("TimeConsumingTest")      // Long-running tests
class TextToActionComprehensiveTests : TextToActionTestBase() {
    // ...
}
```

## 🧠 Test Strategy

### 1. Page Selection Strategy
- Prefer existing test pages: check whether `interactive-*.html` covers the need
- Create a new page only when existing pages can’t cover the scenario (dynamic loading, ambiguity, Shadow DOM, etc.)
- Naming: `interactive-<number>.html` or `<feature>-test.html`

### 2. Test Layers

#### Unit Tests
- Validate a single method
- No real LLM dependency (use mocks)
- Fast and cover edge cases

#### Integration Tests
- Real TTA + LLM interactions
- Use real API calls
- Use Mock Server
- Validate end-to-end translation flow

#### E2E Tests
- Use a real site when necessary
- Full user workflows
- From natural language input to browser actions
- Validate final automation effect

### 3. Test Case Design Principles

#### Positive cases
```kotlin
@Test
fun `When given clear action then generate precise WebDriver code`() = runWebDriverTest { driver ->
    val command = "click the login button"
    val action = textToAction.generateWebDriverAction(command, driver)
    assertThat(action.expressions.joinToString("\n")).contains("driver.click(")
}

@Test
fun `When asking to check element existence then generate exists check`() = runWebDriverTest { driver ->
    val command = "check whether the login button exists"
    val action = textToAction.generateWebDriverAction(command, driver)
    assertThat(action.expressions.joinToString("\n")).contains("driver.exists(")
}

@Test
fun `When asking to verify element visibility then generate visibility check`() = runWebDriverTest { driver ->
    val command = "ensure the submit button is visible"
    val action = textToAction.generateWebDriverAction(command, driver)
    assertThat(action.expressions.joinToString("\n")).contains("driver.isVisible(")
}

@Test
fun `When asking to focus element then generate focus action`() = runWebDriverTest { driver ->
    val command = "focus the search input"
    val action = textToAction.generateWebDriverAction(command, driver)
    assertThat(action.expressions.joinToString("\n")).contains("driver.focus(")
}

@Test
fun `When asking to scroll to element then generate scrollTo action`() = runWebDriverTest { driver ->
    val command = "scroll to the bottom of the page"
    val action = textToAction.generateWebDriverAction(command, driver)
    assertThat(action.expressions.joinToString("\n")).contains("driver.scrollTo(")
}
```

#### Edge cases
```kotlin
@Test
fun `When element does not exist then return empty tool-calls`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("click a non-existent button", driver)
    assertThat(action.expressions.joinToString()).doesNotContain("driver.click(")
}

@Test
fun `When asking for conditional action then include appropriate checks`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("if the login button exists then click it", driver)
    val calls = action.expressions.joinToString()
    assertThat(calls).contains("driver.exists(") || calls.contains("driver.click(")
}

@Test
fun `When asking for defensive interaction then check visibility before action`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("ensure the submit button is visible before clicking", driver)
    val calls = action.expressions.joinToString()
    assertThat(calls).contains("driver.isVisible(") || calls.contains("driver.click(")
}
```

#### Ambiguity/recovery
```kotlin
@Test
fun `When ambiguous command then choose best match or ask to clarify`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("click the button", driver)
    // Verify strategy: prioritize data-testid or closest text match
}

@Test
fun `When asking for navigation sequence then generate proper flow`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("click the link and wait for navigation", driver)
    val calls = action.expressions.joinToString()
    assertThat(calls).contains("driver.click(") || calls.contains("driver.waitForNavigation(")
}

@Test
fun `When asking for complex interaction then generate multi-step sequence`() = runWebDriverTest { driver ->
    val action = textToAction.generateWebDriverAction("scroll to the form, ensure the input is visible, then type text", driver)
    val calls = action.expressions.joinToString()
    assertThat(calls).contains("driver.scrollTo(") ||
                  calls.contains("driver.isVisible(") ||
                  calls.contains("driver.fill(") ||
                  calls.contains("driver.type(")
}
```

## 🎯 High-value Scenarios

### 1. Basic action mapping (aligned with current tool list)
- Click: "click the login button" → `driver.click("#login-btn")`
- Input: "type AI tools in the search box" → `driver.fill("#search-input", "AI tools")`
- Keyboard: "press Enter in the input" → `driver.press("#search-input", "Enter")`
- Scroll: "scroll to the middle of the page" → `driver.scrollToMiddle(0.5)` or `driver.scrollDown(1)`
- Scroll to element: "scroll to the comments section" → `driver.scrollTo("#comments")`
- Screen scroll: "scroll to the middle of the second screen" → `driver.scrollToScreen(1.5)`
- Navigation: "open /docs" → `driver.navigateTo("/docs")`
- Wait for element: "wait for the submit button to appear" → `driver.waitForSelector("#submit-btn", 5000L)`
- Wait for navigation: "after clicking login, wait for redirect" → `driver.click("#login")` + `driver.waitForNavigation()`
- Browser history: "go back" → `driver.goBack()`, "go forward" → `driver.goForward()`
- Element checks: `driver.exists("#element")` and `driver.isVisible("#element")`
- Focus: `driver.focus("#search-input")`
- Check/uncheck: `driver.check("#agree")` / `driver.uncheck("#agree")`
- Advanced clicks (text/attribute matches): `driver.clickTextMatches(".list", "Submit", 1)` / `driver.clickMatches(".item", "data-id", "^x-\\d+$", 1)`
- Click nth link: `driver.clickNthAnchor(3)` (0-based)
- Screenshots: full page `driver.captureScreenshot()` or node `driver.captureScreenshot("#area")`
- Extraction: "extract text content" → `driver.selectFirstTextOrNull("#content")` (Note: selection APIs are not yet exposed as tool calls; current tool mode won't generate these calls)

### 2. Element selection accuracy
- Match by text: "click the submit button"
- By position: "click the top-right menu"
- By function: "select the search box"
- Handle similar elements: precise disambiguation when multiple buttons exist

### 3. Complex scenarios
- Identify elements loaded dynamically
- Match fields in complex forms
- Generate multi-step sequences
- Conditional logic handling
- Defensive programming: exists/isVisible checks before actions
- Navigation handling: waitForNavigation after page transitions
- Complex sequences: scroll → check → focus → input

### 4. Errors and boundaries
- Fallback when element is missing
- Ambiguous instruction handling
- Adapt to DOM structure changes
- Timeouts and exception handling
- Conditional execution failures: element exists but not visible
- Navigation timeout: slow page load handling
- Recovery from a failed step in a multi-step sequence

---

## ✅ Current Tool-call API (available)

Exposed and supported (TTA tools → driver mapping exists):
- navigateTo(url)
- waitForSelector(selector, timeoutMillis)
- exists(selector): Boolean – element existence
- isVisible(selector): Boolean – element visibility
- focus(selector)
- click(selector)
- fill(selector, text)
- press(selector, key)
- check(selector) / uncheck(selector)
- scrollDown(count) / scrollUp(count) / scrollToTop() / scrollToBottom() / scrollToMiddle(ratio)
- scrollTo(selector)
- scrollToScreen(screenNumber)
- clickTextMatches(selector, pattern, count)
- clickMatches(selector, attrName, pattern, count)
- clickNthAnchor(n, rootSelector?)
- waitForNavigation(oldUrl?, timeoutMillis?)
- goBack()
- goForward()
- captureScreenshot() / captureScreenshot(selector)
- delay(millis)
- stop()

MiniWebDriver has more capabilities not yet exposed via tools (recommended to open later):
- State queries: isHidden(selector), isChecked(selector)
- Focus and typing: type(selector, text)
- Precise scroll/positioning: moveMouseTo(x, y) / moveMouseTo(selector, dx, dy)
- Wheel/drag: mouseWheelDown/Up(...), dragAndDrop(selector, dx, dy)
- Page/node extraction: outerHTML(), outerHTML(selector)
- Text/attributes/properties/bulk: selectFirstTextOrNull, selectTextAll, selectFirstAttributeOrNull, selectAttributes, selectAttributeAll
- Properties: selectFirstPropertyValueOrNull, selectPropertyValueAll
- Links/images bulk: selectHyperlinks, selectImages
- Session: getCookies(), url()/currentUrl()/documentURI()/baseURI()/referrer()
- Z-order: bringToFront()

## 💡 Recommended future tool exposure and scenarios

- exists/isVisible:
    - Scenario: "If there is a ‘Buy’ button, click it" → check existence/visibility before click to reduce errors.
- focus/type:
    - Scenario: retain original value or simulate keystrokes (trigger input/keyup listeners).
- scrollTo(selector):
    - Scenario: "Scroll to the comments section and like it" – more robust than generic scroll.
- waitForNavigation/goBack/goForward:
    - Scenario: post-login navigation wait, back/forward navigation.
- mouseWheelDown/Up, moveMouseTo, dragAndDrop:
    - Scenario: canvas, drag-sort, maps/whiteboards, complex scroll containers.
- outerHTML/page extract and bulk selection APIs:
    - Scenario: "Capture all titles/links/images in list", "Export node HTML".
- getCookies/url/referrer:
    - Scenario: verify login state, capture referrers, debug navigation chain.

> Prioritize exposing exists/isVisible/focus/scrollTo(selector)/waitForNavigation first. They significantly improve robustness and observability with low risk.

## 🧩 Interactive elements and context awareness gaps

- Current extraction covers: input/textarea/select/button/a[href], explicit onclick/contenteditable, role=button|link; filters out hidden/disabled.
- Not covered yet:
    - Elements within iframes (context switching needed)
    - Shadow DOM (open/closed)
    - a11y semantics (aria-*, role+name) prioritization
- Test pages already include most directions above; extend extraction scripts and tests as the corresponding pages land.

## ✍️ Doc updates aligned with implementation

- In "Basic action mapping", include: scrollToScreen, clickTextMatches, clickMatches, clickNthAnchor, captureScreenshot(selector)
- In "High-value scenarios", add: nth-link clicking, text/attribute match clicks, partial screenshot
- In "Gaps/roadmap", clarify: iframe/Shadow DOM not supported yet; expand after pages land
- Add "Recommended tools to expose" section (above) as source for future tests

---

## 📈 Coverage Expectations

- Instruction coverage: >90% common user instruction types
- Element types: >85% interactive HTML elements
- Code coverage: >70% (JaCoCo)
- Scenario coverage: 100% of core user flows

---
## ✅ Current test pages capability summary
| Page | Capabilities | Missing |
|------|--------------|---------|
| interactive-1 | Basic input/selection/buttons/show-hide/simple calc | Multi-button ambiguity/error states/long scroll content |
| interactive-2 | Multi-control form/slider/toggle/dynamic fonts | Form validation/multiple steps/conditional display/file/radio |
| interactive-3 | IntersectionObserver animations/range control/show-hide toggle | Real async loading/list add-remove/lazy loading/pagination |
| interactive-4 | Dark mode/drag-and-drop | Cross-list DnD/undo/Shadow DOM/multiple DnD types |
| interactive-dynamic | Delay/async loading/list add-remove | More complex lazy loading/virtual lists |
| interactive-ambiguity | Duplicate elements/ambiguity/data-testid handling | More comprehensive disambiguation/position reference |
| forms-advanced-test | radio/file/date/time/password/disabled/readonly | Fuller validation error states/a11y attributes |
| interactive-screens | Multi-screen scrolling/long page/sectioned content | Real multi-screen/tabs/iframe/segmented/routing awareness |

Conclusion: Add dedicated pages for dynamic async, ambiguity, Shadow DOM, accessibility, media/rich text, and multi-screen structures.

## 🧪 Element type coverage progress (overview)
- Covered: text/email/number/range/textarea/select/checkbox/button/a/draggable list/custom toggle/slider
- Not covered (priority): password/search/date/time/file/radio/progress/meter/dialog/modal/contenteditable/iframe/video/audio/canvas/disabled/readonly/aria-live/Shadow DOM

## 🗺 Roadmap (phased)
1. Phase 1 (structure fixes)
    - Ensure test pages live in pulsar-tests-common/src/main/resources/static/generated/tta
    - Rename interactive-<number>.html to readable names
    - Fix dark mode
    - Rewrite interactive-screens to real multi-screen: Tab + iframe + anchor + long scroll area
2. Phase 2 (dynamic & ambiguity)
    - Add interactive-dynamic.html: async load (setTimeout), list add/remove, lazy images, virtual scroll placeholder
    - Add interactive-ambiguity.html: duplicate buttons/same text across regions/data-testid strategies
3. Phase 3 (advanced controls)
    - forms-advanced-test.html: radio/file/date/time/password/validation errors/disabled/readonly
    - modal-dialog-test.html: custom dialog + focus trap + ESC close
4. Phase 4 (platform/a11y)
    - shadow-components-test.html: open/closed shadow + slot
    - a11y-test.html: landmarks/nav/main/aria-label/aria-live/aria-expanded
    - media-rich-test.html: video/audio/canvas/contenteditable
5. Phase 5 (strategy validation)
    - Element locator priority tests: data-testid > aria-label > role+name > text > relative position
    - Add DOM replacement/stale element retry tests

## 🎯 Locating and naming conventions
- Introduce `data-testid="tta-<domain>-<seq>"` to help disambiguation
- For Shadow DOM demos, wrap outer scope with `data-scope="shadow-demo"`
- Mark dynamically inserted elements with `data-dynamic="true"` for filtering

## 🔁 Recommended helper methods (can be added to base later)
- `waitFor(selector, timeout)` conditional wait
- `retrying(action)` handle transient stale elements
- `byTestId(id)` simplify selectors

## 📌 Acceptance checklist for new/updated pages
- Introduces new element types?
- Includes at least 1 ambiguity scenario?
- Contains dynamic/delayed/failable interactions?
- Provides data-testid / aria metadata?
- Listed in this README capability table?

## 📊 Quality metrics improvements
- Script to count element types in `static/generated/tta/*.html` and report coverage
- Track instruction category distribution (action/target/modifier)
- Classify failures: parse/locate/execute/timeout

---
## 🚀 Test Commands

Windows (cmd.exe):
```
:: Run all TTA tests (paths fixed)
mvnw.cmd test -Dtest="ai.platon.pulsar.skeleton.ai.tta.**"

:: Run a specific test class
mvnw.cmd test -Dtest=TextToActionTest

:: Skip tests requiring LLM (override default excludedGroups if any)
mvnw.cmd test -DexcludedGroups=ExternalServiceTest

:: Generate coverage report (JaCoCo)
mvnw.cmd clean test jacoco:report
```

Linux/macOS:
```
./mvnw test -Dtest="ai.platon.pulsar.skeleton.ai.tta.**"
./mvnw test -Dtest=TextToActionTest
./mvnw test -DexcludedGroups=ExternalServiceTest
./mvnw clean test jacoco:report
```

Notes:
- If parameters include special characters like `.`, use double quotes, e.g. `-Dtest="ai.platon.pulsar.skeleton.ai.tta.**"`.
