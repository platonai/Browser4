# Browser4 Event Demos — Plugin Documentation

Demo plugins demonstrating all **28 event hooks** defined in
[`PageEvents.kt`](../../../../../../../../../../browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/PageEvents.kt).
Each demo logs when its event fires and performs an idiomatic action for that
lifecycle stage.

The demos can be run standalone (every class has a `main()`) and double as a
reference for packaging event handlers as a Browser4 plugin
(`META-INF/browser4-plugin.json` + Spring auto-configuration).

Only the `demos/` package is covered here; the other packages under
`browser4-examples` hold the crawler, agent, LLM, skill, tool and site-specific
example programs.

## File map

```
examples/browser4-examples/
├── pom.xml                                  ← filters ${project.version} into the manifest
└── src/main/
    ├── kotlin/ai/platon/pulsar/examples/demos/
    │   ├── README.md                        ← this file
    │   ├── CrawlEventDemos.kt               ← 2 crawl-phase event handlers
    │   ├── LoadEventDemos.kt                ← 9 load-phase event handlers
    │   ├── BrowseEventDemos.kt              ← 17 browse-phase event handlers
    │   └── config/
    │       └── EventDemosAutoConfiguration.kt ← Spring beans (for PluginManager discovery)
    └── resources/META-INF/
        └── browser4-plugin.json             ← plugin manifest
```

The module does **not** ship
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
so the JAR it builds is not by itself a working plugin: Spring never registers
`EventDemosAutoConfiguration`, an install reports `loaded: false`, and no
handlers are wired (the manifest's `autoConfigurationClasses` entry does not
drive Spring's import). Add that file when packaging — see **Option B** below.

## Event coverage

### CrawlEventHandlers (2 hooks)

Wraps around the load/browse phases. Ideal for URL filtering and result handling.

| # | Event | Signature | Demo behaviour |
|---|---|---|---|
| 1 | `onWillLoad` | `(UrlAware) → UrlAware?` | Logs the URL and warns when it contains `skip-this`; returns the URL unchanged |
| 2 | `onLoaded` | `(UrlAware, WebPage?) → Any?` | Logs protocol status & content length, warns on failure |

Execution: `crawl.onWillLoad → [Load] → [Browse] → crawl.onLoaded`

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

### BrowseEventHandlers (17 hooks)

Controls browser automation: navigation, scrolling, RPA actions. Every handler
except `onWillLaunchBrowser` receives both `(WebPage, WebDriver)`.

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

2 crawl + 9 load + 17 browse = the 28 hooks in `PageEvents.kt`.

---

## Usage

### Option A — Run standalone (development / testing)

Run any demo directly without PluginManager:

```kotlin
// In IntelliJ IDEA (or any runner that puts the module on its classpath),
// run each class's main():
CrawlEventDemos.main()     // 2 events
LoadEventDemos.main()      // 9 events
BrowseEventDemos.main()    // 17 events
```

Each demo class has a `suspend fun run()` instance method and a `companion object`
with `@JvmStatic suspend fun main()`. The `run()` method calls
`session.open(url, eventHandlers)` with a local `DefaultPageEventHandlers` instance
— fully self-contained, no Spring required.

The demos open `TestUrls.PRODUCT_DETAIL_URL` (a live Amazon product page from
`pulsar-tests-common`), so the module's classpath and network access are required.

### Option B — Install as a plugin (production)

The same handlers can be packaged as a Browser4 plugin. The plugin JAR must be a
thin JAR (not a Spring Boot fat JAR): Browser4's plugin classloader reads classes
from the JAR root, so classes hidden under `BOOT-INF/` are invisible and no
`PluginMount` beans are created. For Spring Boot to register the
auto-configuration, the JAR must also contain
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
listing `ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration`. The
manifest's `autoConfigurationClasses` field is used by `PluginService` to report a
plugin as `loaded`; it does not drive Spring's auto-configuration import.

#### 1. Add the auto-configuration entry, then build the JAR

The module ships without the import file (see [File map](#file-map)), so add it
first:

```bash
# From examples/browser4-examples
mkdir -p src/main/resources/META-INF/spring
printf '%s\n' ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration \
  > src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

PowerShell equivalent:

```powershell
New-Item -ItemType Directory -Force src/main/resources/META-INF/spring | Out-Null
Set-Content -NoNewline src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports `
  ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration
```

Then build. Skip the Spring Boot repackage so the JAR stays thin and loadable as
a plugin:

```bash
# From the repo root (JDK 25+ required):
./mvnw package -pl examples/browser4-examples -am -DskipTests \
  -Dspring-boot.repackage.skip=true
# → examples/browser4-examples/target/browser4-examples-4.16.1-SNAPSHOT.jar
```

The file name follows the reactor version — `4.16.1-SNAPSHOT` in this checkout;
adjust the paths below if your version differs.

The JAR contains:
- `META-INF/browser4-plugin.json` — plugin manifest (`version` and `sdkVersion`
  are filtered from `${project.version}` at build time)
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  — the single line added above
- `ai/platon/pulsar/examples/demos/config/EventDemosAutoConfiguration.class`
- All three `*EventDemos.class` files

> **Packaging checklist** (see [`skills/browser4-plugin/SKILL.md`](../../../../../../../../../../skills/browser4-plugin/SKILL.md)):
> 1. thin JAR — no `spring-boot-maven-plugin` `repackage`;
> 2. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
>    listing `ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration`;
> 3. Browser4/Spring dependencies `provided` by the host — this module declares
>    them at `compile` scope, but the thin JAR does not embed them, so the host
>    still supplies them at runtime;
> 4. `sdkVersion` major must match the host's (a newer major is refused, an older
>    same-major one loads with a warning).
>
> These demos also reference `TestUrls` from `pulsar-tests-common`, a test-support
> artifact the runtime bundle does not ship — when installing into such a host,
> inline the URL or bundle that class in the plugin JAR.

#### 2. Install via REST API

```bash
# The bundle/standalone server listens on 18182 by default (server.port).
# Add -F "replace=true" to overwrite an existing JAR with the same file name
# (without it you get HTTP 409 Conflict).
curl -X POST http://localhost:18182/api/plugins/install \
  -H "Content-Type: multipart/form-data" \
  -F "file=@examples/browser4-examples/target/browser4-examples-4.16.1-SNAPSHOT.jar"

# Response (200 OK):
# {
#   "fileName": "browser4-examples-4.16.1-SNAPSHOT.jar",
#   "fileSize": 42000,
#   "path": "/abs/path/to/plugins/browser4-examples-4.16.1-SNAPSHOT.jar",
#   "manifest": {
#     "name": "browser4-event-demos",
#     "version": "4.16.1-SNAPSHOT",
#     "description": "Demo plugins for all 28 events...",
#     "sdkVersion": "4.16.1-SNAPSHOT",
#     "dependsOn": ["browser4-skeleton", "browser4-browser"],
#     "defaultEnabled": true,
#     "autoConfigurationClasses": [
#       "ai.platon.pulsar.examples.demos.config.EventDemosAutoConfiguration"
#     ]
#   },
#   "loaded": false,
#   "defaultEnabled": true,
#   "enabled": true
# }
```

`loaded` is `false` until the application has restarted with the JAR on the
plugin classpath.

> **Loading policy.** `PluginLoadPolicy` decides whether an installed plugin is
> activated. A manifest with `defaultEnabled: false` is opt-in and stays
> inactive unless enabled through `browser4.plugins.enable=browser4-event-demos`,
> `browser4.plugins.enable-all=true`, or their `BROWSER4_PLUGINS_ENABLE` /
> `BROWSER4_PLUGINS_ENABLE_ALL` env vars. An explicit
> `browser4.plugins.disable` always wins. This demo omits `defaultEnabled`, so
> the manifest default (`true`) applies and it loads automatically.

#### 3. Restart the application

On restart the `PluginManager` (an `ApplicationRunner`) fires:

```
--- PluginManager: scanning for plugins ---
Found 3 PluginMount bean(s)
  - crawlEventDemosMount : CrawlEventDemos
  - loadEventDemosMount : LoadEventDemos
  - browseEventDemosMount : BrowseEventDemos
  + Configured crawl event handlers
  + Configured load event handlers
  + Configured browse event handlers
Found 0 Browser4Plugin bean(s)
--- PluginManager: scan complete ---
```

`PluginManager` calls `PulsarEventBus.ensurePageEventHandlers()` before wiring, so
the global handler chains the browser emulator fires are the same ones the plugin
mounts are added to. All 28 handlers are now active on every page load.

#### 4. Verify

```bash
# List installed plugins
curl http://localhost:18182/api/plugins
# → [{"fileName":"browser4-examples-4.16.1-SNAPSHOT.jar","loaded":true, ...}]

# Inspect one (by manifest name or JAR file name)
curl http://localhost:18182/api/plugins/browser4-event-demos

# Tail logs to see handlers firing
# → [Crawl.onWillLoad] About to load: https://...
# → [Load.onNormalize] Original URL: https://...
# → [Browse.onBrowserLaunched] Browser launched for: https://...
# → ...
```

#### 5. Uninstall

```bash
curl -X DELETE http://localhost:18182/api/plugins/browser4-event-demos
# → 200 OK (JAR deleted from plugins/; beans remain until restart)
```

The plugin is matched by manifest name or by JAR file name (with or without the
`.jar` extension). On Windows a locked JAR returns HTTP 409 — stop the
application, delete it, then restart.

---

## How it fits together

```
┌──────────────────────────────────────────────────────────────────┐
│ PluginController (REST)                                          │
│ POST /api/plugins/install  ← upload JAR (replace=true)           │
│ GET  /api/plugins           ← list plugins                       │
│ GET  /api/plugins/{name}    ← inspect one plugin                 │
│ DELETE /api/plugins/{name}  ← remove                             │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ PluginService                                                    │
│ • Validates META-INF/browser4-plugin.json                        │
│ • Checks sdkVersion against the running host                     │
│ • Copies JAR → plugins/ directory                                │
└──────────────────────┬───────────────────────────────────────────┘
                       │ (after restart)
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│ PluginManager (ApplicationRunner)                                │
│ • Scans Spring context for PluginMount beans                     │
│ • Calls configure*Handlers() on each mount                       │
│ • Wires handlers into PulsarEventBus.ensurePageEventHandlers()   │
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
| `PageSummaryAlgorithmMount` | `getPageSummaryAlgorithms()` | same |
| `PageFormatContributorMount` | `getPageFormatContributors()` | same |
| `ToolMount` | `getToolExecutors()` | `browser4-agentic/.../tools/ToolMount.kt` |
| `PageSnifferMount` | `getPageSniffers()` | `browser4-protocol/.../emulator/util/PageSnifferMount.kt` |
| `Browser4Plugin` | `manifest`, `onStartup()`, `onShutdown()` | `browser4-skeleton/.../plugin/Browser4Plugin.kt` |
| `PluginManifest` | `name`, `version`, `description`, `sdkVersion`, `dependsOn`, `defaultEnabled`, `autoConfigurationClasses` | `browser4-skeleton/.../plugin/PluginManifest.kt` |

`PluginManifest.fromJar()` falls back to the JAR's `Browser4-Plugin-Version`
manifest attribute when `sdkVersion` is absent, so older plugins still carry a
version. `defaultEnabled` is read by `PluginLoadPolicy` (see the loading policy
note above).

## Creating your own plugin

1. Implement one or more `PluginMount` sub-interfaces — the event mounts
   (`CrawlEventMount`, `LoadEventMount`, `BrowseEventMount`), `ToolMount`,
   `PageSnifferMount`, `PageSummaryAlgorithmMount`, or
   `PageFormatContributorMount`
2. Create a Spring `@AutoConfiguration` (annotate it `@Lazy`) that exposes your mount as a `@Bean`
3. Add `META-INF/browser4-plugin.json` listing your auto-configuration class
4. Add `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with the same class name
5. Build a thin JAR (no `spring-boot-maven-plugin` `repackage`) and install via `POST /api/plugins/install`

See `CaptchaAutoConfiguration` in `browser4-plugins/browser4-captcha/` for a
production example that implements `BrowseEventMount`, `ToolMount`, and
`PageSnifferMount`. For the full walkthrough (archetype, mount points, manifest,
testing, deployment), see
[`skills/browser4-plugin/SKILL.md`](../../../../../../../../../../skills/browser4-plugin/SKILL.md).
