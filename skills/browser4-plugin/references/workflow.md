---
title: "Step-by-Step Workflow"
description: "Use when building a Browser4 plugin end to end — clarifying requirements, choosing PluginMount interfaces, scaffolding, implementing, testing, and deploying."
tier: procedure
---

# Step-by-Step Workflow

## Quick Start

```bash
mvn -pl browser4-pdk install          # install the PDK parent POM once
mvn archetype:generate -DarchetypeGroupId=ai.platon.pulsar \
    -DarchetypeArtifactId=browser4-plugin-archetype   # scaffold the plugin project
mvn -f <artifactId>/pom.xml package   # build the plugin JAR
```

The project builds to a thin JAR (`target/<artifactId>-<version>.jar`) deployable to Browser4's `plugins/` directory. The plugin is auto-discovered on restart and logs the mounted beans.

## When to Use

Follow this walkthrough when creating, building, or extending a Browser4 plugin from scratch: requirements clarification, mount-point selection, scaffolding, implementation (event handlers, services, LLM agent tools), Spring auto-configuration wiring, tests, build, and deployment. It is the deep guide behind the [SKILL.md](../SKILL.md) patterns.

Prerequisites:

- JDK 17+
- Maven 3.9+
- Access to the Browser4 PDK parent POM (`ai.platon.pulsar:browser4-pdk`) — published to Maven Central
- For building the archetype locally: `mvn -pl browser4-pdk install` from this repository

## How It Works

Eight steps take a plugin from idea to deployment: clarify requirements, choose the `PluginMount` interfaces, scaffold the project from the Maven archetype, implement the mount points, implement services and config, create the plugin manifest and auto-configuration imports, write tests, then build, verify, and deploy. Each step is self-contained and builds on the previous one.

## Patterns

### Step 1: Clarify Requirements

Before writing any code, ask the user these questions. The answers determine which `PluginMount` interfaces and project structure to use.

| Question | Why it matters |
|----------|----------------|
| What is the plugin's core purpose? | Determines which `PluginMount` interfaces to implement and what services are needed |
| Should the plugin act automatically on every page, or only when explicitly invoked? | Automatic action → `BrowseEventMount` on `onDocumentSteady`. Explicit invocation → `ToolMount`. Both → implement both. |
| Does the plugin need to transform or extract data during page loading? | Yes → `LoadEventMount` or `CrawlEventMount`. No → skip these. |
| Should LLM agents be able to invoke the plugin's features as tools? | Yes → `ToolMount`. No → skip. |
| Does the plugin recognize a new category of page (CAPTCHA, paywall, etc.)? | Yes → `PageSnifferMount`. No → skip. |
| What external services or APIs does the plugin use? | Determines additional dependencies (OkHttp client, AWS SDK, etc.) and configuration properties. |
| Should the plugin be configurable (enabled/disabled, timeouts, endpoints)? | Yes → create a `Config` data class and `@ConditionalOnProperty` annotations. |
| Does the plugin need explicit startup/shutdown lifecycle hooks? | Yes → implement `Browser4Plugin`. No → auto-configuration only is sufficient. |

### Step 2: Choose PluginMount Interfaces

Map the plugin's capabilities to one or more mount points. The auto-configuration class implements all chosen interfaces.

| If the plugin needs to... | Implement | Primary hook |
|---------------------------|-----------|-------------|
| Execute code when a page finishes loading and the DOM is stable | `BrowseEventMount` | `onDocumentSteady` — the recommended RPA hook |
| Intercept or block resources before navigation | `BrowseEventMount` | `onWillNavigate` — call `driver.addBlockedURLs()` |
| Take screenshots or extract data before tab closes | `BrowseEventMount` | `onWillStopTab` — last chance before tab teardown |
| Normalize or modify URLs before fetching | `LoadEventMount` | `onNormalize` |
| Extract data right after HTML is parsed | `LoadEventMount` | `onHTMLDocumentParsed` |
| Filter which URLs enter the crawl pipeline | `CrawlEventMount` | `onWillLoad` — return `null` to reject |
| Expose new LLM-callable functions | `ToolMount` | `getToolExecutors()` — must return a `List<ToolExecutor>` |
| Detect a page category (e.g., "this is a CAPTCHA page") | `PageSnifferMount` | `getPageSniffers()` — must return a `List<PageCategorySniffer>` |
| Have startup/shutdown lifecycle hooks | `Browser4Plugin` | Override `onStartup()` / `onShutdown()` |

**Common combinations from built-in plugins:**

| Plugin | Mount points used | Pattern |
|--------|------------------|---------|
| CAPTCHA | `BrowseEventMount` + `ToolMount` + `PageSnifferMount` | Auto-detect + manual solve + page classification |
| Images | `BrowseEventMount` + `ToolMount` + `Browser4Plugin` | Auto-detect + agent tool + explicit lifecycle |
| Media | `BrowseEventMount` + `ToolMount` | Auto-detect + agent tool |
| PPTX | `BrowseEventMount` + `ToolMount` | Agent-invoked conversion |
| Markdown | `BrowseEventMount` + `ToolMount` | Agent-invoked conversion |
| PDK Test | `BrowseEventMount` + `LoadEventMount` + `CrawlEventMount` | All event phases (reference/canary) |

### Step 3: Scaffold via the Archetype

Run the Maven archetype command from the Quick Start with the full parameter set (see [Flags / Options](#flags--options)):

```bash
mvn archetype:generate \
  -DarchetypeGroupId=ai.platon.pulsar \
  -DarchetypeArtifactId=browser4-plugin-archetype \
  -DarchetypeVersion=4.12.0 \
  -DgroupId=com.example \
  -DartifactId=browser4-myfeature \
  -Dversion=1.0.0-SNAPSHOT \
  -DpluginName="My Feature" \
  -DpluginDescription="A Browser4 plugin that performs custom page processing"
```

The generated project contains:

```text
browser4-<feature>/
├── pom.xml                          # Maven build (parent: browser4-pdk)
├── .gitignore
├── README.md
└── src/main/
    ├── kotlin/<package>/
    │   ├── MyPlugin.kt              # Optional: Browser4Plugin lifecycle
    │   ├── config/
    │   │   └── PluginAutoConfiguration.kt  # Required: @AutoConfiguration + mounts
    │   ├── integration/
    │   │   ├── MyBrowseEventHandler.kt     # Optional: browse event handler
    │   │   └── MyLoadEventHandler.kt       # Optional: load event handler
    │   └── tools/
    │       └── MyToolExecutor.kt           # Optional: LLM agent tool
    └── resources/
        └── META-INF/
            ├── browser4-plugin.json                # Required: plugin manifest
            └── spring/
                └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  # Required
```

**Immediately after scaffolding, do these renames:**

1. Rename the generated `PluginAutoConfiguration` class to `<Feature>AutoConfiguration` (e.g., `CaptchaAutoConfiguration`)
2. Rename `MyPlugin` to `<Feature>Plugin` (if keeping the lifecycle class)
3. Rename handler classes: `MyBrowseEventHandler` → `<Feature>BrowseEventHandler`
4. Update `browser4-plugin.json` — change the `name`, `description`, and `autoConfigurationClasses` to match
5. Update `AutoConfiguration.imports` — replace the FQN with the renamed class
6. Delete stub files you won't use (removing unused files is better than leaving dead code)

### Step 4: Implement Mount Points

#### 4a. BrowseEventMount — Custom RPA on Every Page

For plugins that execute code automatically when a page loads. The key hook is `onDocumentSteady`, which fires when the DOM is fully rendered and the page is stable.

```kotlin
@AutoConfiguration
@ConditionalOnProperty(name = ["myfeature.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class MyFeatureAutoConfiguration : BrowseEventMount {

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        // Primary RPA hook — fires when DOM is fully rendered
        handlers.onDocumentSteady.addLast { page, driver ->
            // Access page.url, page.content, driver.*
            // Custom logic here
        }

        // Optional: block unwanted resources before navigation
        handlers.onWillNavigate.addLast { page, driver ->
            driver.addBlockedURLs(listOf("*.png", "*.jpg", "*analytics*"))
        }
    }
}
```

**Browse event hooks in execution order:**

| Position | Hook | Best for |
|----------|------|----------|
| 1 | `onWillLaunchBrowser` | Pre-launch setup |
| 2 | `onBrowserLaunched` | First access to `WebDriver` |
| 3 | `onWillFetch` | Pre-fetch configuration |
| 4 | `onWillNavigate` | **Block resources, set headers** |
| 5 | `onNavigated` | Post-navigation checks |
| 6 | `onWillInteract` | Pre-interaction setup |
| 7 | `onWillCheckDocumentState` | Check readyState |
| 8 | `onDocumentFullyLoaded` | DOM ready |
| 9 | `onWillScroll` | Pre-scroll |
| 10 | `onDidScroll` | Post-scroll |
| 11 | `onDocumentSteady` | **★ Best for custom RPA** |
| 12 | `onWillComputeFeature` | Pre-feature computation |
| 13 | `onFeatureComputed` | Features computed |
| 14 | `onDidInteract` | All interactions complete |
| 15 | `onWillStopTab` | **Last chance before tab close** |
| 16 | `onTabStopped` | Tab stopped |
| 17 | `onFetched` | Fetch complete |

#### 4b. LoadEventMount — Content Interception During Loading

For plugins that normalize URLs, extract data during parsing, or transform content.

```kotlin
override fun configureLoadHandlers(handlers: LoadEventHandlers) {
    // Strip tracking parameters from all URLs before loading
    handlers.onNormalize.addLast { url ->
        url.replace(Regex("\\?utm_.*"), "")
    }

    // Extract data right after HTML parsing (doc is a Jsoup Document)
    handlers.onHTMLDocumentParsed.addLast { page, doc ->
        // Parse and extract structured data from doc
    }
}
```

**Load event hooks:** `onNormalize` → `onWillLoad` → `onWillFetch` → `onFetched` → `onWillParse` → `onWillParseHTMLDocument` → `onHTMLDocumentParsed` (★ best for data extraction) → `onParsed` → `onLoaded`

#### 4c. CrawlEventMount — URL Pipeline Filtering

For plugins that accept/reject URLs in the crawl pipeline.

```kotlin
override fun configureCrawlHandlers(handlers: CrawlEventHandlers) {
    handlers.onWillLoad.addLast { url ->
        if (isBlacklisted(url.url)) null else url  // null = reject
    }
}
```

**Crawl hooks:** `onWillLoad` (return `null` to reject URL) → `onLoaded` (results are available)

#### 4d. ToolMount — LLM Agent Tools

For plugins whose features should be invocable by AI agents. Extend `AbstractToolExecutor` from `browser4-agentic`.

```kotlin
// In the auto-configuration class:
class MyFeatureAutoConfiguration : ToolMount {
    override fun getToolExecutors(): List<ToolExecutor> =
        listOf(applicationContext.getBean("myFeatureToolExecutor") as ToolExecutor)
}

// In tools/MyFeatureToolExecutor.kt:
open class MyFeatureToolExecutor(
    private val service: MyFeatureService,
) : AbstractToolExecutor() {
    override val domain = "myfeature"

    init {
        toolSpec["doSomething"] = ToolSpec(
            domain = domain,
            method = "doSomething",
            arguments = listOf(
                ToolSpec.Arg("param1", "String"),
                ToolSpec.Arg("param2", "Int?", "0"),
            ),
            returnType = "MyResult",
            description = "Does something useful with the current page"
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val driver = receiver as? WebDriver
        return when (functionName) {
            "doSomething" -> service.execute(driver, args)
            else -> throw IllegalArgumentException("Unknown method: $functionName")
        }
    }
}
```

#### 4e. PageSnifferMount — Page Category Detection

For plugins that recognize new page types. Implement `PageCategorySniffer` and return it.

#### 4f. Browser4Plugin — Lifecycle Hooks (Optional)

Implement only if you need explicit startup/shutdown callbacks:

```kotlin
open class MyFeaturePlugin(
    override val manifest: PluginManifest = PluginManifest(
        name = "browser4-myfeature",
        version = "4.12.0-rc.1",
        description = "My plugin description",
        dependsOn = listOf("browser4-protocol", "browser4-agentic"),
        autoConfigurationClasses = listOf(
            "ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration"
        )
    )
) : Browser4Plugin {
    override fun onStartup() { /* initialize resources */ }
    override fun onShutdown() { /* release resources */ }
}
```

### Step 5: Implement Services and Config

Separate business logic from mount-point wiring. The auto-configuration class wires dependencies; the service class contains business logic; the config data class holds tunable settings.

```kotlin
// config/MyFeatureConfig.kt
data class MyFeatureConfig(
    val enabled: Boolean = true,
    val timeoutSeconds: Long = 30,
    val endpoint: String = "https://default.example.com",
) {
    companion object {
        private const val PREFIX = "myfeature."
        fun fromConfig(conf: Config): MyFeatureConfig = MyFeatureConfig(
            enabled = conf.getBoolean("${PREFIX}enabled", true),
            timeoutSeconds = conf.getLong("${PREFIX}timeout.seconds", 30),
            endpoint = conf.get("${PREFIX}endpoint", "https://default.example.com"),
        )
    }
}

// service/MyFeatureService.kt
open class MyFeatureService(
    private val config: MyFeatureConfig,
) {
    suspend fun process(page: WebPage, driver: WebDriver): Result {
        // Business logic — use driver.evaluate() for JavaScript,
        // OkHttpClient for HTTP calls, etc.
    }
}
```

### Step 6: Create Plugin Manifest and Auto-Configuration Imports

Two mandatory resource files in every plugin JAR:

**`src/main/resources/META-INF/browser4-plugin.json`:**

```json
{
  "name": "browser4-myfeature",
  "version": "4.12.0-rc.1",
  "description": "A Browser4 plugin that provides custom page processing functionality",
  "dependsOn": ["browser4-protocol", "browser4-agentic"],
  "defaultEnabled": true,
  "autoConfigurationClasses": [
    "ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration"
  ]
}
```

`defaultEnabled` decides which loading category the plugin belongs to:

- `true` (default) — **default-loaded**: the plugin activates automatically.
- `false` — **opt-in / default-disabled**: the plugin is skipped unless explicitly enabled with `browser4.plugins.enable=<name>` or `browser4.plugins.enable-all=true`.

See [Plugin Loading](plugin-loading.md) for the full loading model and overrides.

**`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** (single line):

```text
ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration
```

### Step 7: Write Tests

Place tests in `src/test/kotlin/` mirroring the source package. Use JUnit 5 + `kotlin-test-junit5` + `spring-boot-test` (all `test` scope).

**Config test pattern:**

```kotlin
@Test
fun `test config defaults`() {
    val config = MyFeatureConfig()
    assertTrue(config.enabled)
    assertEquals(30, config.timeoutSeconds)
}

@Test
fun `test fromConfig reads properties`() {
    val conf = Config.of(mapOf("myfeature.enabled" to "false"))
    val config = MyFeatureConfig.fromConfig(conf)
    assertFalse(config.enabled)
}
```

**Service test pattern — use `java.lang.reflect.Proxy` for lightweight mocks:**

```kotlin
@Suppress("UNCHECKED_CAST")
private fun webPageProxy(): WebPage {
    return Proxy.newProxyInstance(
        WebPage::class.java.classLoader,
        arrayOf(WebPage::class.java)
    ) { _, _, _ -> null } as WebPage
}
```

**Event handler test pattern — use `runBlocking` for coroutine testing:**

```kotlin
@Test
fun `test browse event handler processes page`() = runBlocking {
    val handler = MyFeatureService(mockConfig)
    val result = handler.process(webPageProxy(), mockDriver)
    assertNotNull(result)
}
```

### Step 8: Build, Verify, and Deploy

```bash
# Build the thin JAR
mvn package -DskipTests

# Verify the JAR structure (optional but recommended)
# bin/verify-plugin.ps1 target/browser4-myfeature-1.0.0-SNAPSHOT.jar

# Deploy — copy to Browser4's plugins/ directory and restart
cp target/browser4-myfeature-1.0.0-SNAPSHOT.jar /path/to/browser4/plugins/
# Or install via REST API
curl -X POST http://localhost:8182/api/plugins/install \
  -F "file=@target/browser4-myfeature-1.0.0-SNAPSHOT.jar"
```

After restart, check application logs for:

```text
PluginManager: Found X PluginMount bean(s)
PluginManager:   ✓ Configured browse event handlers
PluginManager: Found X Browser4Plugin bean(s)
  - browser4-myfeature v4.12.0-rc.1
```

## Flags / Options

The archetype takes named parameters; there are no CLI flags. The full parameter table (groupId, artifactId, pluginName, mountPoints, hasCustomTools, hasLifecycle, features, …) lives in [SKILL.md Flags](../SKILL.md#flags). Parameters are passed as `-D<name>=<value>` to `mvn archetype:generate` — see Step 3 for a complete example command.

## Errors & Recovery

> **Note:** Full symptom → cause → fix table — see [SKILL.md Errors & Recovery](../SKILL.md#errors--recovery).
