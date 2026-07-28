# Browser4 Event Demos — Plugin Documentation

Demo plugins demonstrating all **28 event hooks** defined in
[`PageEvents.kt`](../../../../../../../../../../browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/PageEvents.kt).
Each demo logs when its event fires and performs an idiomatic action for that
lifecycle stage.

## File map

```
demos/
├── README.md                          ← this file
├── CrawlEventDemos.kt                 ← 2 crawl-phase event handlers
├── LoadEventDemos.kt                  ← 9 load-phase event handlers
├── BrowseEventDemos.kt                ← 17 browse-phase event handlers
└── config/
    └── EventDemosAutoConfiguration.kt ← Spring beans (for PluginManager discovery)

../resources/META-INF/
└── browser4-plugin.json               ← plugin manifest
```

## Event coverage

### CrawlEventHandlers (2 hooks)

Wraps around the load/browse phases. Ideal for URL filtering and result handling.

| # | Event | Signature | Demo behaviour |
|---|---|---|---|
| 1 | `onWillLoad` | `(UrlAware) → UrlAware?` | Filters URLs containing `skip-this`; attaches remark metadata |
| 2 | `onLoaded` | `(UrlAware, WebPage?) → Any?` | Logs protocol status & content length, warns on failure |

Execution: `crawl.onWillLoad → [Load] → [Browse] → crawl.onLoaded`

---

### LoadEventHandlers (9 hooks)

Manages URL normalization, fetching, and parsing.

| # | Event | Signature | Demo behaviour |
|---|---|---|---|
| 1 | `onNormalize` | `(String) → String?` | Strips `utm_*` params & `#fragments` |
| 2 | `onWillLoad` | `(String) → String?` | Rejects blank URLs |
| 3 | `onWillFetch` | `(WebPage) → Any?` | Logs fetch attempt count |
| 4 | `onFetched` | `(WebPage) → Any?` | Logs status & byte size |
| 5 | `onWillParse` | `(WebPage) → Any?` | Logs parse start |
| 6 | `onWillParseHTMLDocument` | `(WebPage) → Any?` | Logs HTML parse start |
| 7 | `onHTMLDocumentParsed` ★ | `(WebPage, FeaturedDocument) → Any?` | Extracts title, meta, body preview, link count |
| 8 | `onParsed` | `(WebPage) → Any?` | Logs parse complete |
| 9 | `onLoaded` | `(WebPage) → Any?` | Logs final status |

Execution:
```
onNormalize → onWillLoad → onWillFetch → [Browse Phase] → onFetched →
onWillParse → onWillParseHTMLDocument → onHTMLDocumentParsed → onParsed → onLoaded
```

---

### BrowseEventHandlers (17 hooks)

Controls browser automation: navigation, scrolling, RPA actions. All handlers
after `onWillLaunchBrowser` receive both `(WebPage, WebDriver)`.

| # | Event | Signature | Demo behaviour |
|---|---|---|---|
| 1 | `onWillLaunchBrowser` | `(WebPage) → Any?` | Logs pre-launch (no WebDriver yet) |
| 2 | `onBrowserLaunched` | `suspend (WP, WD) → Any?` | Injects `window.__browser4Demo` init script |
| 3 | `onWillFetch` | `suspend (WP, WD) → Any?` | Logs pre-fetch |
| 4 | `onWillNavigate` | `suspend (WP, WD) → Any?` | Blocks images/fonts/analytics via `addBlockedURLs` |
| 5 | `onNavigated` | `suspend (WP, WD) → Any?` | Waits for `.main-content` selector (10s timeout) |
| 6 | `onWillInteract` | `suspend (WP, WD) → Any?` | Logs start of interaction phase |
| 7 | `onWillCheckDocumentState` | `suspend (WP, WD) → Any?` | Evaluates `document.readyState` |
| 8 | `onDocumentFullyLoaded` | `suspend (WP, WD) → Any?` | Logs page height & viewport |
| 9 | `onWillScroll` | `suspend (WP, WD) → Any?` | Logs pre-scroll Y position |
| 10 | `onDidScroll` | `suspend (WP, WD) → Any?` | Logs final vs max scroll position |
| 11 | `onDocumentSteady` ★ | `suspend (WP, WD) → Any?` | Clicks "Show More" if present; dismisses cookie banners |
| 12 | `onWillComputeFeature` | `suspend (WP, WD) → Any?` | Logs feature computation start |
| 13 | `onFeatureComputed` | `suspend (WP, WD) → Any?` | Counts images & links on page |
| 14 | `onDidInteract` | `suspend (WP, WD) → Any?` | Logs interaction completion |
| 15 | `onWillStopTab` | `suspend (WP, WD) → Any?` | Captures `document.cookie` before tab closes |
| 16 | `onTabStopped` | `suspend (WP, WD) → Any?` | Logs tab stopped (final browse event) |
| 17 | `onFetched` | `suspend (WP, WD) → Any?` | Logs final URL after all interactions |

Execution:
```
onWillLaunchBrowser → onBrowserLaunched → onWillFetch → onWillNavigate →
onNavigated → onWillInteract → onWillCheckDocumentState → onDocumentFullyLoaded →
onWillScroll → onDidScroll → onDocumentSteady ★ → onWillComputeFeature →
onFeatureComputed → onDidInteract → onWillStopTab → onTabStopped → onFetched
```

★ = recommended event for custom actions (document stable, all content loaded).

---

## Usage

### Option A — Run standalone (development / testing)

Run any demo directly without PluginManager:

```kotlin
// In IntelliJ or via Gradle — each class has a @JvmStatic companion main():
CrawlEventDemos.main()    // 2 events
LoadEventDemos.main()      // 9 events
BrowseEventDemos.main()    // 17 events
```

Each demo class has a `suspend fun run()` instance method and a `companion object`
with `@JvmStatic suspend fun main()`. The `run()` method calls
`session.open(url, eventHandlers)` with a local `DefaultPageEventHandlers` instance
— fully self-contained, no Spring required.

### Option B — Install as a plugin (production)

#### 1. Build the JAR

```bash
# From the repo root
mvn package -pl examples/browser4-examples -am -DskipTests
# → examples/browser4-examples/target/browser4-examples-4.12.0-SNAPSHOT.jar
```

The JAR contains:
- `META-INF/browser4-plugin.json` — plugin manifest
- `ai/platon/pulsar/examples/demos/config/EventDemosAutoConfiguration.class`
- All three `*EventDemos.class` files

#### 2. Install via REST API

```bash
# Upload the plugin JAR
curl -X POST http://localhost:8080/api/plugins/install \
  -H "Content-Type: multipart/form-data" \
  -F "file=@examples/browser4-examples/target/browser4-examples-4.12.0-SNAPSHOT.jar"

# Response (200 OK):
# {
#   "fileName": "browser4-examples-4.12.0-SNAPSHOT.jar",
#   "fileSize": 42000,
#   "path": "/abs/path/to/plugins/browser4-examples-4.12.0-SNAPSHOT.jar",
#   "manifest": {
#     "name": "browser4-event-demos",
#     "version": "4.12.0-SNAPSHOT",
#     "description": "Demo plugins for all 28 events...",
#     "dependsOn": ["browser4-skeleton", "browser4-browser"],
#     "autoConfigurationClasses": [
#       "ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration"
#     ]
#   },
#   "loaded": false
# }
```

#### 3. Restart the application

On restart the `PluginManager` (an `ApplicationRunner`) fires:

```
--- PluginManager: scanning for plugins ---
Found 3 PluginMount bean(s)
  - crawlEventDemosMount : CrawlEventDemos
  ✓ Configured crawl event handlers
  - loadEventDemosMount : LoadEventDemos
  ✓ Configured load event handlers
  - browseEventDemosMount : BrowseEventDemos
  ✓ Configured browse event handlers
Found 0 Browser4Plugin bean(s)
--- PluginManager: scan complete ---
```

All 28 handlers are now wired into `PulsarEventBus.pageEventHandlers` and
fire on every page load.

#### 4. Verify

```bash
# List installed plugins
curl http://localhost:8080/api/plugins
# → [{"fileName":"browser4-examples-4.12.0-SNAPSHOT.jar", "loaded":true, ...}]

# Inspect one
curl http://localhost:8080/api/plugins/browser4-event-demos

# Tail logs to see handlers firing
# → [Crawl.onWillLoad] About to load: https://...
# → [Load.onNormalize] Original URL: https://...
# → [Browse.onBrowserLaunched] Browser launched for: https://...
# → ...
```

#### 5. Uninstall

```bash
curl -X DELETE http://localhost:8080/api/plugins/browser4-event-demos
# → 200 OK (JAR deleted from plugins/; beans remain until restart)
```

---

## How it fits together

```
┌──────────────────────────────────────────────────────────────────┐
│ PluginController (REST)                                          │
│ POST /api/plugins/install  ← upload JAR                         │
│ GET  /api/plugins           ← list plugins                       │
│ DELETE /api/plugins/{name}  ← remove                             │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ PluginService                                                    │
│ • Validates META-INF/browser4-plugin.json                        │
│ • Copies JAR → plugins/ directory                                │
└──────────────────────┬───────────────────────────────────────────┘
                       │ (after restart)
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ PluginManager (ApplicationRunner)                                │
│ • Scans Spring context for PluginMount beans                     │
│ • Calls configure*Handlers() on each mount                       │
│ • Wires handlers into PulsarEventBus.pageEventHandlers           │
└──────────────────────┬───────────────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   CrawlEventMount  LoadEventMount  BrowseEventMount
   (2 handlers)     (9 handlers)    (17 handlers)
          │            │            │
          └────────────┼────────────┘
                       ▼
              PulsarEventBus
        (global event handler chain)
```

## Key interfaces

| Interface | Method | Source |
|---|---|---|
| `PluginMount` | (marker) | `browser4-skeleton/.../plugin/MountPoints.kt` |
| `CrawlEventMount` | `configureCrawlHandlers(CrawlEventHandlers)` | same |
| `LoadEventMount` | `configureLoadHandlers(LoadEventHandlers)` | same |
| `BrowseEventMount` | `configureBrowseHandlers(BrowseEventHandlers)` | same |
| `Browser4Plugin` | `manifest`, `onStartup()`, `onShutdown()` | `browser4-skeleton/.../plugin/Browser4Plugin.kt` |

## Creating your own plugin

1. Implement one or more `*EventMount` interfaces
2. Create a Spring `@AutoConfiguration` that exposes your mount as a `@Bean`
3. Add `META-INF/browser4-plugin.json` listing your auto-configuration class
4. Build the JAR and install via `POST /api/plugins/install`

See `CaptchaAutoConfiguration` in `browser4-plugins/browser4-captcha/` for a
production example that implements `BrowseEventMount`, `ToolMount`, and
`PageSnifferMount`.
