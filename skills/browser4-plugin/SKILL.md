---
name: browser4-plugin
title: "Browser4 Plugin Development"
description: "Guides the creation of Browser4 plugins from requirements gathering through deployment. Use when the user wants to create, build, scaffold, or extend Browser4 with a new plugin — whether for CAPTCHA solving, media processing, content conversion, page category detection, custom RPA actions, or new LLM agent tools."
tier: procedure
---

# Browser4 Plugin Development

## Quick Start

```bash
mvn -pl browser4-pdk install          # install the PDK parent POM once
mvn archetype:generate -DarchetypeGroupId=ai.platon.pulsar \
    -DarchetypeArtifactId=browser4-plugin-archetype   # scaffold the plugin project
mvn -f <artifactId>/pom.xml package   # build the plugin JAR
```

Deploy the JAR to the server's `plugins/` directory (or POST it to `http://localhost:8182/api/plugins/install`) and restart — the plugin is auto-discovered. Prerequisites: JDK 17+, Maven 3.9+, and access to the `browser4-pdk` parent POM.

For the full walkthrough — requirements clarification, mount-point implementation, services/config, manifest, tests, build & deploy — see [Step-by-Step Workflow](references/workflow.md).

## When to Use

Use this skill when the user wants to **create, build, scaffold, or extend a Browser4 plugin** — CAPTCHA solving, media processing, content conversion, page-category detection, custom RPA actions, or new LLM agent tools. It is the deep guide (mount points, services, tests, deployment); for plugin *code* scaffolding inside this repo use the `browser4-coding` skill.

**Do NOT create a plugin for:** simple data extraction from a known site (use the browser4-cli HTML snapshot / X-SQL instead), one-off browser automation (the CLI or a quick script is faster), or modifying core Browser4 behavior (that belongs in the main source tree, not a plugin).

## How It Works

A plugin is a thin JAR built against the `browser4-pdk` parent POM: you scaffold the project from the archetype, implement the relevant `PluginMount` interfaces (Browse/Load/Crawl event mounts, ToolMount, PageSnifferMount), wire Spring auto-configuration, and build. The server auto-discovers the JAR from `plugins/` on restart and logs the mounted beans.

## Patterns

### 1. Event-handling plugin (BrowseEventMount)

Scaffold with `mountPoints=["BrowseEventMount"]`, implement the browse handler, and register it via auto-configuration. The key hook is `onDocumentSteady` — see [Step 4a](references/workflow.md#4a-browseeventmount--custom-rpa-on-every-page) for the full code.

### 2. Plugin with LLM agent tools (ToolMount)

Scaffold with `mountPoints=["ToolMount"]` and `hasCustomTools=true`, implement a `ToolExecutor` extending `AbstractToolExecutor`, and declare the tool in plugin.json — see [Step 4d](references/workflow.md#4d-toolmount--llm-agent-tools).

### 3. Full-featured plugin (all major patterns)

Model it on the first-party plugins — `browser4-plugins/browser4-images/` implements BrowseEventMount + ToolMount + Browser4Plugin + Config + Service + BrowseEventHandler + ToolExecutor.

## Flags

The archetype takes named parameters — there are no CLI flags:

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `groupId` | String | Yes | — | Maven groupId for the new plugin project (e.g., `com.example`) |
| `artifactId` | String | Yes | — | Maven artifactId, conventionally `browser4-<feature-name>` |
| `version` | String | No | `1.0.0-SNAPSHOT` | Plugin version |
| `pluginName` | String | Yes | — | Human-readable plugin name (e.g., `"My Feature Plugin"`) |
| `pluginDescription` | String | No | `"A Browser4 plugin that provides custom functionality"` | One-line description |
| `mountPoints` | String[] | No | `["BrowseEventMount"]` | Which `PluginMount` interfaces to implement. Options: `BrowseEventMount`, `LoadEventMount`, `CrawlEventMount`, `ToolMount`, `PageSnifferMount` |
| `hasCustomTools` | Boolean | No | `false` | Whether the plugin registers LLM agent tool executors |
| `hasLifecycle` | Boolean | No | `false` | Whether the plugin implements the `Browser4Plugin` lifecycle interface |
| `features` | String[] | No | — | List of concrete capabilities to implement (e.g., `"detect media on page"`, `"download files"`, `"expose LLM tool"`) |

## When to Create a Plugin

Create a plugin when you need to:

- **Hook into the browse lifecycle** — execute custom logic on page-navigation events (before navigation, after DOM is steady, before tab close). Examples: CAPTCHA detection and solving, ad blocking, custom RPA workflows.
- **Hook into the load lifecycle** — intercept or transform page content during loading. Examples: URL normalization and stripping tracking parameters, content extraction, HTML post-processing.
- **Hook into the crawl lifecycle** — accept or reject URLs during crawling. Examples: domain allowlists, duplicate URL filtering, paywall detection and skip.
- **Register custom tools for LLM agents** — expose new capabilities as callable functions. Examples: image download, PPTX generation, database queries, API integrations.
- **Add page category sniffers** — teach Browser4 to recognize new page types so it can adapt its behavior. Examples: CAPTCHA pages, login pages, paywalls, shopping carts.

## Errors & Recovery

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Plugin not loaded at startup; no "registered plugin" log | `browser4-plugin.json` missing, malformed, or JAR not in the plugins directory | Verify JAR is in the configured plugins directory; verify JSON is valid; verify `autoConfigurationClasses` FQN matches |
| Plugin skipped as "default-disabled (opt-in)" | Manifest has `defaultEnabled: false` and no explicit enable override | Enable with `browser4.plugins.enable=<name>` or `browser4.plugins.enable-all=true`, or set `defaultEnabled: true` |
| `ClassNotFoundException` for Browser4 API classes | Dependency scope is `compile` instead of `provided` | Change all `browser4-*` and Spring Boot deps to `<scope>provided</scope>` |
| Mount point handlers never fire | Auto-configuration class doesn't implement the correct `PluginMount` interface, or `AutoConfiguration.imports` file is missing/wrong | Verify `AutoConfiguration.imports` contains the exact FQN; verify the auto-config class implements the mount interface |
| `BeanCreationException` at startup | A bean dependency is missing or circular | Check bean constructor args; ensure `@Lazy` on the auto-config class |
| Tool executor not available to agents | `getToolExecutors()` returns empty list, or executor not exposed as a Spring bean | Verify the list contains beans from `applicationContext`; verify executor has `@Bean` in auto-config |
| Archetype generation fails | Maven can't resolve `browser4-plugin-archetype` | Build the PDK locally first: `mvn -pl browser4-pdk install` |
| `onDocumentSteady` handler throws silently | Missing try-catch in handler body | Always wrap handler body in try-catch; log errors via a logger |
| Config properties have no effect | Config class not reading from `Config` or property prefix mismatch | Verify `fromConfig()` method reads all keys with correct prefix; verify property names in application config |
| `NoSuchMethodError` at runtime | Plugin compiled against a different Browser4 version than the host | Rebuild with matching `browser4-pdk` version; or reinstall the matching Browser4 version |
| JAR contains embedded dependencies | Fat JAR instead of thin JAR — `spring-boot-maven-plugin` with `repackage` goal | Remove or skip the `repackage` goal; ensure only `maven-jar-plugin` is active |
| `NoClassDefFoundError` for third-party libraries | Third-party dependency not bundled in the plugin JAR | Use `compile` scope for third-party deps (not `provided`) so they are included in the JAR |

## Critical Warnings

> **Warning:** All `browser4-*` and Spring Boot dependencies must use `<scope>provided</scope>`. Using `compile` scope causes `ClassNotFoundException` at runtime because the host application provides these classes. The plugin JAR should only bundle your own code and true third-party libraries (use `compile` scope for those).

> **Warning:** Always annotate the auto-configuration class with `@Lazy`. Plugin beans depend on services that may not be available until the application context is fully initialized. Without `@Lazy`, startup may fail with `BeanCreationException`.

> **Warning:** The `browser4-plugin.json` manifest and the `Browser4Plugin.manifest` property must stay in sync. The JSON file is always required for JAR discovery; the `Browser4Plugin` interface is optional. If both are present, the JSON manifest is authoritative for the plugin registry.

> **Warning:** Event handlers run on the browser event loop. Never perform blocking I/O (HTTP calls, file writes) directly in a handler — use coroutines. For `ToolMount` executors, the framework handles coroutine dispatch automatically.

> **Warning:** An uncaught exception in one event handler can break the entire handler chain. Always wrap handler bodies in try-catch and log failures.

> **Note:** For lightweight plugin development without cloning the full Browser4 repo, the `browser4-pdk` parent POM extends `pulsar-parent` from Maven Central. Third-party developers only need the archetype and a Maven installation.

> **Tip:** Build and test incrementally. After scaffolding, immediately run `mvn package` to verify the build works before writing any custom code. Then implement one mount point at a time, rebuilding and testing after each.

> **Tip:** Read the `browser4-pdk-test-plugin` source before writing complex handlers. It demonstrates every mount point and serves as the compatibility canary.

> **Tip:** When using `@ConditionalOnProperty`, prefer `matchIfMissing = true` so the plugin is enabled by default unless explicitly disabled. This matches the convention used by all first-party plugins.

> **Tip:** The `browser4-plugins/browser4-images/` plugin is the best all-around reference — it implements `BrowseEventMount` + `ToolMount` + `Browser4Plugin` with a config data class, service layer, browse event handler, and tool executor.

## Reference Map

I want to... | Read
--- | ---
Walk through the full plugin lifecycle (requirements → scaffold → deploy) | [Step-by-Step Workflow](references/workflow.md)
Know which files a plugin project contains and what each is for | [File Reference](references/file-reference.md)
Understand default vs opt-in loading and enable overrides | [Plugin Loading](references/plugin-loading.md)

Key source files to read for patterns and examples:

| Resource | Path | What it demonstrates |
|----------|------|---------------------|
| Canonical test plugin | `browser4-pdk/browser4-pdk-test-plugin/` | All three event-phase mount points (BrowseEventMount, LoadEventMount, CrawlEventMount) — the compatibility canary |
| Plugin archetype | `browser4-pdk/browser4-plugin-archetype/src/main/resources/archetype-resources/` | Scaffolded project structure, templates for all required files |
| PluginMount interfaces | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/MountPoints.kt` | `PluginMount`, `BrowseEventMount`, `LoadEventMount`, `CrawlEventMount` interface definitions |
| Event lifecycle | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/PageEvents.kt` | `LoadEventHandlers`, `BrowseEventHandlers`, `CrawlEventHandlers` — all 28 hooks |
| Event handler types | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/EventHandlers.kt` | Chainable handler function types (`WebPageWebDriverEventHandler`, etc.) |
| Plugin manifest | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/PluginManifest.kt` | `PluginManifest` data class schema |
| ToolMount + registry | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolMount.kt` | `ToolMount` interface + `CustomToolRegistry` singleton |
| ToolExecutor base | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/AbstractToolExecutor.kt` | `ToolExecutor` interface and `AbstractToolExecutor` base class |
| PDK parent POM | `browser4-pdk/pom.xml` | Parent POM for plugin projects (standalone — inherits from `pulsar-parent` on Maven Central) |
| Plugin dev docs | `docs/plugin-development.md` | Full plugin development guide with API reference |
| **Most complete reference plugin** | `browser4-plugins/browser4-images/` | Implements BrowseEventMount + ToolMount + Browser4Plugin + Config + Service + BrowseEventHandler + ToolExecutor — all major patterns |
| CAPTCHA plugin | `browser4-plugins/browser4-captcha/` | Reference for PageSnifferMount + multi-tool executor |
| PDK test plugin source | `browser4-pdk/browser4-pdk-test-plugin/src/main/kotlin/ai/platon/pulsar/pdk/testplugin/config/TestPluginAutoConfiguration.kt` | Minimal mount-point wiring for all three event phases |

## See Also

- [Plugin Development Guide](../../docs-dev/plugin-development.md) — Official plugin development documentation
- [AGENTS.md](../../AGENTS.md) — Project architecture, build commands, code style, and testing conventions
- [PDK Test Plugin](../../browser4-pdk/browser4-pdk-test-plugin/) — Minimal reference plugin implementing all mount points
- [Built-in Plugins](../../browser4-plugins/) — Five first-party plugin implementations (captcha, images, media, pptx, markdown)
