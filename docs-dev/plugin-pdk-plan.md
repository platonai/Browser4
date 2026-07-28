# Browser4 Plugin Development Kit (PDK) — Implementation Plan

## Context

Browser4 has a well-designed plugin system (PluginMount, Browser4Plugin, Spring Boot auto-configuration, PluginClasspathEnhancer), but third-party developers currently **cannot create plugins without cloning the entire Browser4 repository**. This is because plugin POMs must inherit from the `browser4` parent POM and use `browser4-dependencies` BOM — both requiring the full repo.

**Goal**: Enable third-party developers to create, build, and install Browser4 plugins as standalone Maven/Gradle projects, using only published Maven Central artifacts.

## Target Developer Workflow

```bash
# 1. Create a new plugin project
mvn archetype:generate \
  -DarchetypeGroupId=ai.platon.pulsar \
  -DarchetypeArtifactId=browser4-plugin-archetype

# 2. Build the plugin
cd my-browser4-plugin && mvn package

# 3. Install into running Browser4
curl -X POST http://localhost:8080/api/plugins/install \
  -F "file=@target/my-browser4-plugin-1.0.0.jar"

# 4. Restart Browser4 → plugin is live
```

## Current Plugin Architecture (Summary)

### Plugin interfaces (in `browser4-skeleton`)
- **`PluginMount`** — marker interface, base of all mount points
- **`LoadEventMount`** — 9 load-phase event hooks (normalize, fetch, parse, loaded)
- **`BrowseEventMount`** — 17 browse-phase event hooks (navigate, scroll, interact, RPA)
- **`CrawlEventMount`** — 2 crawl-phase hooks (URL filter, result handling)
- **`Browser4Plugin`** — optional lifecycle interface (`onStartup`/`onShutdown` + `PluginManifest`)

### Additional mounts
- **`ToolMount`** (in `browser4-agentic`) — register custom LLM agent tool executors
- **`PageSnifferMount`** (in `browser4-protocol`) — register page category sniffers

### Plugin loading mechanism
1. `PluginClasspathEnhancer` scans `plugins/` dir, creates URLClassLoader before Spring Boot starts
2. Spring Boot discovers `META-INF/spring/...AutoConfiguration.imports` in plugin JARs
3. `PluginManager` (ApplicationRunner) discovers `PluginMount` beans and wires them into `PulsarEventBus`/`CustomToolRegistry`/`BrowserResponseHandler`

### Plugin JAR structure
```
my-plugin.jar
├── META-INF/
│   ├── browser4-plugin.json          (required)
│   └── spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  (required)
└── com/example/myplugin/
    ├── config/PluginAutoConfiguration.kt
    ├── integration/MyBrowseEventHandler.kt
    └── tools/MyToolExecutor.kt
```

## What to Build

### 1. New top-level module: `browser4-pdk/`

Three sub-artifacts:

**a) `browser4-pdk-bom`** (POM, published to Maven Central)
- A standalone BOM that third-party projects `import`-scope. Self-contained — no parent inheritance needed.
- Imports: `spring-boot-dependencies`, `pulsar-bom`, `kotlin-bom`, `kotlinx-coroutines-bom`
- Manages versions for: `browser4-skeleton`, `browser4-browser`, `browser4-common`, `browser4-protocol` (optional), `browser4-agentic` (optional)
- Reference: `browser4-dependencies/pom.xml` for version values

**b) `browser4-pdk` parent POM** (POM, published to Maven Central)
- Standalone parent for third-party plugin projects (`<relativePath/>` — always resolves from Central)
- Configures Kotlin 2.3.21 + JVM 17 compiler
- Configures Maven plugins (compiler, jar, resources) — **no** `spring-boot-maven-plugin repackage`
- Imports `browser4-pdk-bom` for dependency management

**c) `browser4-plugin-archetype`** (Maven archetype JAR, published to Maven Central)
- `mvn archetype:generate` target that scaffolds a working plugin project
- Template files: `pom.xml`, `PluginAutoConfiguration.kt`, `MyBrowseEventHandler.kt`, `MyToolExecutor.kt`, `MyPlugin.kt`, `browser4-plugin.json`, `AutoConfiguration.imports`
- Properties: `browser4-version`, `pluginName`, `pluginPackage`, `pluginDescription`

### 2. Test/reference plugin: `browser4-pdk-test-plugin`

- Lives in the Browser4 repo, depends on `browser4-pdk` parent with `provided`-scope Browser4 API deps
- Demonstrates all mount point types
- CI builds it to verify the PDK works end-to-end
- Serves as living documentation

### 3. Plugin verification script

- `bin/verify-plugin.sh` (and `.ps1`) — validates a built plugin JAR:
  - `META-INF/browser4-plugin.json` exists and is valid JSON
  - `autoConfigurationClasses` is non-empty
  - `AutoConfiguration.imports` exists and references valid class
  - JAR is thin (no dependency JARs inside)
  - Classes compiled to Java 17 bytecode

### 4. Documentation: `docs/plugin-development.md`

Covers: getting started, plugin API reference (all mount points + event hooks), Spring auto-configuration, plugin JAR structure, tool executor development, building/testing, deployment (REST API + manual), troubleshooting.

### 5. GitHub template repository (optional but recommended)

`github.com/platonai/browser4-plugin-template` — forkable template with same content as archetype output.

## Key Design Decisions

| Decision | Rationale |
|---|---|
| **`provided` scope** for all Browser4/Spring/Kotlin deps | Host Browser4 already has them on classpath; including them in plugin JAR causes ClassLoader conflicts |
| **Thin JAR only** (no Spring Boot repackage) | Fat JARs use `PropertiesLauncher` incompatible with `PluginClasspathEnhancer`'s `URLClassLoader` |
| **New standalone BOM** vs modifying `browser4-dependencies` | `browser4-dependencies` relies on `browser4` parent POM properties; PDK BOM must be fully self-describing |
| **Maven archetype over Gradle** | The host uses Maven; published SDK should match. Gradle template provided separately in Git template repo |

## Prerequisites (Phase 0 — must verify first)

Before building the PDK, confirm these artifacts are published to Maven Central:

- `ai.platon.pulsar:browser4-common`
- `ai.platon.pulsar:browser4-browser`
- `ai.platon.pulsar:browser4-skeleton`
- `ai.platon.pulsar:browser4-protocol`
- `ai.platon.pulsar:browser4-agentic`
- Transitive: `pulsar-bom:4.9.2`, `pulsar-common`, `pulsar-persist`, `pulsar-dom`, `pulsar-ql-common`, `pulsar-h2`, `pulsar-jsoup`
- Transitive: `ai.platon.cdt:cdt-kotlin-client:4.8.0`

If any are missing, fix the publishing pipeline first.

## Implementation Sequence

| Phase | Steps | Effort |
|---|---|---|
| **P0: Audit** | Verify Maven Central publishing status; fix gaps | 3-4 days |
| **P1: PDK core** | Create `browser4-pdk` parent POM + `browser4-pdk-bom` | 2-3 days |
| **P2: Archetype** | Create `browser4-plugin-archetype` with templates | 3 days |
| **P3: Test + CI** | Create `browser4-pdk-test-plugin`, JAR validator, CI job | 3-4 days |
| **P4: Docs** | Write `docs/plugin-development.md`, quickstart scripts | 2-3 days |
| **P5: Publish** | Deploy PDK artifacts to Maven Central; tag release | 1-2 days |

**Total: ~14-18 days**

## Key Files to Reference

- `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/MountPoints.kt` — PluginMount interfaces (API contract)
- `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/Browser4Plugin.kt` — Plugin lifecycle interface
- `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/PluginManifest.kt` — Manifest schema
- `browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginManager.kt` — Runtime wiring logic
- `browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginClasspathEnhancer.kt` — Classpath loading
- `browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginService.kt` — JAR management
- `browser4-plugins/browser4-captcha/` — Best example of a well-structured plugin (manifest + auto-config + mounts)
- `examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/examples/demos/config/EventDemosAutoConfiguration.kt` — Simplest clean example
- `browser4-dependencies/pom.xml` — Version catalog to replicate in PDK BOM

## Verification

1. **Phase 0**: Run `mvn dependency:resolve` on test project using only Maven Central — all artifacts resolve
2. **Phase 3**: CI builds `browser4-pdk-test-plugin` against published artifacts successfully
3. **Phase 3**: `verify-plugin.sh` passes all checks on test plugin JAR
4. **Phase 3**: Deploy test plugin to running Browser4 instance, confirm PluginManager discovers and wires it
5. **Phase 5**: Third-party developer can run `mvn archetype:generate`, `mvn package`, `curl POST /api/plugins/install`, restart, and see plugin active
