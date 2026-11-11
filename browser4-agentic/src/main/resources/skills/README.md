# Skills Directory Structure

This directory contains six definition-backed agent skills following the standardized directory
structure pattern used by Browser4 4.16.1-SNAPSHOT. A seventh skill, `browser4-cli`, is copied into
the agentic jar at build time from the repository-level `skills/` directory; see
[Bundled CLI Skill Documents](#bundled-cli-skill-documents).

A skill is a self-contained directory whose `SKILL.md` declares metadata, parameters, and usage.
Optional `scripts/`, `references/`, and `assets/` folders provide executable helpers, developer
documentation, and templates. At startup `SkillBootstrap` discovers skills on the classpath, adapts
each `SKILL.md` into a `DefinitionBackedSkill`, resolves dependencies through `SkillLoader`, and
registers them in `SkillRegistry`.

Skill versions are independent of the Browser4 release: the four skills that declare
`metadata.version` all start at `1.0.0`, and the two that omit it fall back to that same default, so
the `1.0.0` values in this document are skill versions, not Browser4 versions. See
[Versioning](#versioning) for the two version spaces.

## Versioning

Browser4 and its bundled skills are versioned independently:

| Version | Scope | Source |
|---------|-------|--------|
| `4.16.1-SNAPSHOT` | Browser4 release | root `pom.xml` |
| `1.0.0` | Initial version of each bundled skill (a skill contract version, not a Browser4 release) | `metadata.version` in `SKILL.md` (the loader default when omitted) |

A skill version describes that skill's own contract. Bump it when the skill changes, and do not
rewrite it to match the Browser4 release. `web-scraping`, `form-filling`, `data-validation`, and
`weather` declare `metadata.version` explicitly; `pdf` and `mcp-builder` omit it today, so they
resolve to the `1.0.0` default.

## Directory Structure

Each skill follows this structure:

```
skills/
├── README.md         # This file
└── skill-name/
    ├── SKILL.md      # Required: Skill instructions and metadata
    ├── LICENSE.txt   # Optional: License for bundled material
    ├── scripts/      # Optional: Executable scripts and examples
    ├── references/   # Optional: Developer documentation
    └── assets/       # Optional: Configuration, templates, resources
```

All six definition-backed skills in this directory live in a directory of this shape; two arrange
their documentation files differently:

- `mcp-builder/` uses a singular `reference/` directory for its four design guides:
  `evaluation.md`, `mcp_best_practices.md`, `node_mcp_server.md`, and `python_mcp_server.md`.
- `pdf/` keeps `forms.md` and `reference.md` beside `SKILL.md`; root-level files are read from the
  skill directory and are not exposed through `skill.readReference`.

`skill.readReference` accepts both `references/` and `reference/`, and only Python files under
`scripts/` can be executed through `skill.runScript`. `SkillDefinitionLoader` records only the
standard plural directories, so `mcp-builder`'s `reference/` files are readable through
`skill.readReference` while the skill is served from an exploded classpath or a filesystem
directory, but they are not reported as `referencesPath` by `skill.activate` and are not copied
when a skill is extracted from a JAR — a packaged skill exposes only its plural directories.
Installed skills use the same layout under `AgentPaths.SKILLS_DIR` (`<app data>/agent/skills`,
typically `~/.browser4/agent/skills`).

## Available Skills

### [Web Scraping](./web-scraping/SKILL.md)
Extract data from web pages using browser automation and CSS/JavaScript selectors.

**Key Features:**
- JavaScript-based extraction through `WebDriver`, with a `PulsarSession` fallback
- Page title and body-text extraction
- `skill.debug.scraping.extract` tool specification for direct agent calls
- Selector and attribute metadata in the result, with `attributes` defaulting to `["text"]`
- Headless-first guidance with a one-shot headed escalation for anti-bot challenges
- Text capped at 5,000 characters to keep responses manageable

**Bundled resources:** `scripts/example-usage.kts`, `references/developer-guide.md`,
`assets/config.json`, `assets/selectors-template.md`

### [Form Filling](./form-filling/SKILL.md)
Automatically fill web forms with provided data.

**Key Features:**
- Field-name/value mapping
- Optional form submission flag
- Text, email, password, textarea, select, checkbox, and radio inputs
- Dependency on `web-scraping` for field and structure detection
- Multi-step forms through shared skill context
- CSRF-token and hidden-field handling
- Security guidance for sensitive form data

**Bundled resources:** `scripts/example-usage.kts`, `references/developer-guide.md`,
`assets/config.json`, `assets/form-data-template.md`

### [Data Validation](./data-validation/SKILL.md)
Validate data against specified rules.

**Key Features:**
- Email validation
- Required fields checking
- Extensible rule system with custom-rule and subclassing hooks
- Comprehensive error reporting that runs every rule before returning

**Bundled resources:** `scripts/example-usage.kts`, `references/developer-guide.md`,
`assets/config.json`, `assets/validation-rules-template.md`

### [PDF Processing](./pdf/SKILL.md)
Extract text and tables from PDF files, fill PDF forms, and create/merge/split PDF documents.

**Key Features:**
- Text and table extraction from PDFs
- PDF form filling (fillable fields)
- PDF creation, merging, splitting, page rotation, and metadata extraction
- Form field inspection and annotation

**Bundled resources:** `scripts/` (8 Python files: 7 helpers and 1 test), `forms.md`, `reference.md`,
`LICENSE.txt`

### [MCP Builder](./mcp-builder/SKILL.md)
Guide for creating advanced MCP (Model Context Protocol) servers that enable LLMs to interact with external services through well-designed tools.

**Key Features:**
- Python (FastMCP) and Node/TypeScript (MCP SDK) support
- Deep research and planning phase
- API coverage vs. workflow tool design
- Evaluation-driven quality assurance

**Bundled resources:** `scripts/connections.py`, `scripts/evaluation.py`,
`scripts/example_evaluation.xml`, `scripts/requirements.txt`, `reference/` (four guides:
`evaluation.md`, `mcp_best_practices.md`, `node_mcp_server.md`, `python_mcp_server.md`),
`LICENSE.txt`

### [Weather](./weather/SKILL.md)
Fetch current weather conditions and a 7-day forecast for a named location.

**Key Features:**
- No API key required
- Current conditions plus exactly 7 forecast days
- Metric or imperial units, with an optional ISO 3166-1 country code to disambiguate places
- Open-Meteo geocoding for free-form place names
- WMO code summaries plus precipitation, wind, humidity, sunrise, and sunset values
- Standard-library-only Python and machine-readable JSON output for agents

**Bundled resources:** `scripts/get_weather.py`

### Bundled Resource Summary

| Skill | `scripts/` | `references/` | `assets/` | Extra files |
|-------|------------|---------------|-----------|-------------|
| `web-scraping` | `example-usage.kts` | `developer-guide.md` | `config.json`, `selectors-template.md` | - |
| `form-filling` | `example-usage.kts` | `developer-guide.md` | `config.json`, `form-data-template.md` | - |
| `data-validation` | `example-usage.kts` | `developer-guide.md` | `config.json`, `validation-rules-template.md` | - |
| `pdf` | 8 Python files (7 helpers + 1 test) | - | - | `forms.md`, `reference.md`, `LICENSE.txt` |
| `mcp-builder` | `connections.py`, `evaluation.py`, `example_evaluation.xml`, `requirements.txt` | `reference/` (4 guides) | - | `LICENSE.txt` |
| `weather` | `get_weather.py` | - | - | - |
| `browser4-cli` (build-time) | - | `references/` (46 guides) | - | `SKILL.md`, `methodology.md`; copied from the repo-level `skills/browser4-cli/` into the jar |

The `browser4-cli` row is not a source directory in this tree: `browser4-agentic/pom.xml` copies it
into the jar at classpath `skills/browser4-cli/**`, so it is available to a packaged runtime even
though it is not one of the six skills above. See
[Bundled CLI Skill Documents](#bundled-cli-skill-documents).

Only `pdf` and `weather` declare `allowed-tools: python`, so they are the only bundled skills whose
`scripts/*.py` files can be executed through `skill.runScript`. The Python files under
`mcp-builder/scripts/` are reference implementations and evaluation helpers, not runnable skill
scripts.

## Bundled CLI Skill Documents

The `browser4-cli` skill is maintained at
[skills/browser4-cli/SKILL.md](../../../../../skills/browser4-cli/SKILL.md) rather than under this
directory. `browser4-agentic/pom.xml` adds `${maven.multiModuleProjectDirectory}/skills` as a
resource root and copies `browser4-cli/**` to classpath `skills/browser4-cli/**`, which is how the
CLI guide reaches a packaged runtime without a source tree (see the skill-resource packaging section
of [robust-browser-agent-run-v2-design.md](../../../../../docs-dev/copilot/robust-browser-agent-run-v2-design.md)).
The copied tree contains `SKILL.md`, `methodology.md`, and 46 guides under `references/`.

The CLI agent reads these documents through the `system` tool domain instead of `skill.*`:

| Tool | Arguments | Description |
|------|-----------|-------------|
| `system.skillDoc` | `name` (String), `offset` (Int, optional), `limit` (Int, default 8000) | Read a bundled `browser4-cli` document or one page of it |

`SystemToolExecutor.skillDoc` resolves `/skills/browser4-cli/<name>` first and then
`/skills/browser4-cli/references/<name>`, after stripping a leading `/`, `skills/`, or
`browser4-cli/` and rejecting `..` and backslashes. With no `offset`, `SKILL.md` returns a bounded
reference map (its top-level section headings plus the linked reference docs and their offsets, at
most 2,048 characters); any other document returns its first slice. Offsets are code points,
`limit` defaults to 8,000 and is clamped to 256..16,000, and a document is capped at 120,000
characters. A returned slice carries `offset`, `nextOffset`, and `totalLength` in its header so the
caller can page without overlap.

`skillDocMetadata` returns only the YAML frontmatter for system prompts, and `skillDocStrict`
returns `null` for a missing document (otherwise the body with its frontmatter stripped), which lets
the caller embed a resident quick reference or choose its own fallback. The curated index returned
for an unknown name lives in `SystemToolExecutor.AVAILABLE_DOCS`; that list is only a discovery
hint, because every `.md` document bundled under `/skills/browser4-cli/` or
`/skills/browser4-cli/references/` can be read by name, including documents the list does not name.

## Skill Format

`SKILL.md` starts with YAML frontmatter followed by the markdown body:

```markdown
---
name: my-skill
description: One-line description of what the skill does and when to use it.
metadata:
  displayName: My Skill
  version: "1.0.0"
  author: Your Name
  tags: "tag1, tag2"
  dependencies: "web-scraping, data-validation"
allowed-tools: python
---

# My Skill Name

## Description

Brief description of what the skill does.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| param1 | String | Yes | - | Description |
| param2 | Boolean | No | false | Description |

## Usage Examples

Provide code examples showing how to use the skill.

## Error Handling

Document common errors and failure cases.

## See Also

Link to related skills and documentation.
```

Frontmatter rules parsed by `SkillDefinitionLoader` (registration-time checks are noted):

| Field | Required | Notes |
|-------|----------|-------|
| `name` | Yes | Lowercase kebab-case, 1-64 characters, no consecutive hyphens, and must match the directory name |
| `description` | Yes | 1-1024 characters |
| `metadata.displayName` | No | Human-readable name used by the registry; defaults to `name` |
| `metadata.version` | No | Three-part numeric version (major.minor.patch), defaults to `1.0.0`. The loader accepts the string as written; `SkillMetadata` validates the format when the skill is registered |
| `metadata.author` | No | Defaults to an empty string |
| `metadata.tags` | No | Comma- or space-separated string, or a YAML list |
| `metadata.dependencies` | No | Comma- or space-separated string, or a YAML list of skill IDs |
| `allowed-tools` | No | Space-separated tool names; `python` enables `skill.runScript` for `scripts/*.py` |
| `license` | No | License identifier or a reference to a bundled `LICENSE.txt` |
| `compatibility` | No | Environment or version notes, at most 500 characters |

`version`, `author`, `tags`, and `dependencies` are also accepted as top-level keys. Other
`metadata.*` keys are preserved in `SkillDefinition.metadata`.

The legacy markdown format (`## Metadata` with `- **Skill ID**: ...` bullets) is still parsed for
backward compatibility, but new skills should use YAML frontmatter.

A `## Installation`, `## Install`, `## Setup`, or `## Getting Started` section is parsed into
`SkillDefinition.installSteps` and returned by `skill.install`; both numbered and bulleted steps are
recognized.

## Skill Tools

Registered skills are exposed to the agent through the `skill` tool domain:

| Tool | Arguments | Description |
|------|-----------|-------------|
| `skill.list` | `maxDescriptionChars` (Int, default 512) | List short skill summaries for discovery and matching |
| `skill.activate` | `id` | Return the full `SKILL.md` plus any detected `scripts/`, `references/`, and `assets/` paths |
| `skill.run` | `id`, `params` (map or JSON string) | Run a registered skill by ID with an optional parameter map |
| `skill.install` | `sourceDir`, `overwrite` (Boolean, default false) | Install a skill from a source directory containing `SKILL.md` |
| `skill.uninstall` | `id` | Remove an installed skill's files and unregister it |
| `skill.readDocumentation` | `id` | Read the full `SKILL.md` for a registered skill |
| `skill.readReference` | `id`, `path` (under `references/` or `reference/`) | Read a reference file |
| `skill.runScript` | `id`, `path` (under `scripts/`), `args`, `timeoutMillis` (1000..120000, default 10000), `maxOutputChars` (1000..200000, default 20000) | Run a Python script with a timeout and output cap |

`skill.list` returns `SkillSummary` objects (`id`, `name`, `description`, `version`, `tags`) with
each description truncated to `maxDescriptionChars`. `skill.activate` returns a `SkillActivation`
with the full `SKILL.md`, its `id`/`name`/`version`, and the resolved `scriptsPath`,
`referencesPath`, and `assetsPath` when those directories exist.

`skill.info` and `skill.reload` are not part of this agent-facing executor; they belong to the
REST/MCP management surface described under
[Installing and Uninstalling Skills](#installing-and-uninstalling-skills).

`skill.run` executes programmatic skills. Skills loaded from `SKILL.md` are definition-backed and
return metadata only until a concrete executor is supplied; use `skill.runScript` to run their
bundled scripts. `skill.readReference` and `skill.runScript` accept only resource-backed
(`DefinitionBackedSkill`) skills, and `skill.runScript` launches `python` from `PATH` with the skill
root as the working directory.

Programmatic skills may also contribute their own tool specifications; `SkillToolExecutor` merges
them with the stable `skill.*` entry points (for example, `WebScrapingSkill` adds a
`skill.debug.scraping.extract` specification under its own `skill.debug.scraping` domain).

`skill.run`, `skill.runScript`, `skill.install`, `skill.uninstall`, and the management `skill.reload`
execute code or write files, so they require full access in the task's access policy. Discovery and
documentation reads do not.

## Skill Events

`SkillToolExecutor` publishes skill activity through
`AgentEventBus.emitSkillEvent` without blocking the caller. The event constants live in
`AgenticEvents.Skill`:

| Event | Emitted when |
|-------|--------------|
| `skill.onSkillsListed` | After `skill.list` returns, with the skill count and description limit |
| `skill.onSkillActivated` | After `skill.activate` loads the full documentation |
| `skill.onWillRunSkill` | Before `skill.run` dispatches to the skill |
| `skill.onDidRunSkill` | After `skill.run` returns, with duration and a success flag |
| `skill.onSkillError` | When `skill.run` throws, with the error message and duration |
| `skill.onSkillInstalled` | After `skill.install` finishes, with the install result |
| `skill.onSkillUninstalled` | After `skill.uninstall` finishes, with the uninstall result |

The payload carries the `agentId` (the session ID) plus event-specific metadata such as `skillId`,
`count`, `maxDescriptionChars`, `paramsKeys`, `duration`, `success`, `message`, and `error`.

## Installing and Uninstalling Skills

`SkillInstaller` implements the document-driven workflow behind `skill.install`:

1. **Read** — load `SKILL.md` from the source directory and parse its frontmatter and install steps.
2. **Validate** — require a `SKILL.md`, reject missing dependencies, and refuse to replace an
   existing skill unless `overwrite=true`.
3. **Deploy** — copy the whole skill directory to `AgentPaths.SKILLS_DIR/<skillId>`
   (`<app data>/agent/skills/<skillId>`).
4. **Register** — reload the deployed definition as a `DefinitionBackedSkill` and register it.

`skill.install` returns an `InstallResult` with `success`, `skillId`, `message`, `installSteps`, and
`deployedPath`. `skill.uninstall` unregisters the skill (refusing while another skill depends on it)
and removes the deployed directory; `skill.readDocumentation` reads the same `SKILL.md` through
activation.

The REST layer wraps the same installer in `SkillService` (`browser4-boot`) and exposes management
endpoints under `/api/skills`:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/skills` | List registered skills as `SkillDetail`-shaped summaries (detail-only fields are empty) |
| `GET` | `/api/skills/{id}` | Read one skill as a `SkillDetail` |
| `POST` | `/api/skills/install` | Install from a server-side `path` or a multipart `file` zip, with optional `overwrite` |
| `DELETE` | `/api/skills/{id}` | Uninstall a skill |
| `POST` | `/api/skills/{id}/reload` | Reload a filesystem-backed skill from its source directory |

`SkillMCPToolExecutor` (`browser4-rest`) mounts the management surface as a parallel set of
`skill.*` MCP tools: `skill.list`, `skill.info`, `skill.install` (takes a server-side `path`, unlike
the execution-side `sourceDir`), `skill.uninstall`, and `skill.reload`. `skill.info` returns a
`SkillDetail` with metadata, the full `SKILL.md`, resource paths, and origin; `skill.reload` re-reads
a filesystem-backed skill from its source directory.

## How Skills Are Loaded

`SkillBootstrap` runs at application startup. `SkillAutoConfiguration` registers it with
`@Lazy(false)`, so the startup load still happens when `spring.main.lazy-initialization=true`.
It clears the registry, then loads skills in this order:

1. Classpath resources matching `classpath*:skills/**/SKILL.md` — the six skills in this directory,
   plus the `browser4-cli` copy that Maven adds when the module is packaged
2. The example programmatic skill `WebScrapingSkill`, registered only when a classpath definition
   has not already claimed the `web-scraping` ID
3. User-provided skills from `AgentPaths.SKILLS_DIR`

The `browser4-cli` entry appears only after Maven's `process-resources` phase copies the extra
resource directory; a raw source tree contains just the six in-tree skills. Because its `SKILL.md`
is a normal definition, `browser4-cli` is also registered by `SkillBootstrap` in a packaged runtime
and can be listed and activated like the others, while `system.skillDoc` serves its documents
directly from the classpath.

`SkillLoader.loadAll` resolves dependencies before registering a skill. Registration fails when a
dependency is missing, when a skill's `validate` hook returns false, or when the ID is already
registered. A programmatic skill whose ID is already provided by a classpath skill is skipped, so
resource-backed skills take precedence.

When several classpath entries define the same skill ID, `SkillDefinitionLoader` keeps the first
definition it scans and logs the conflict. For skills packaged in a JAR, it extracts `SKILL.md` plus
the files under the standard `scripts/`, `references/`, and `assets/` directories into a temporary
directory so activation and script tools have real paths to work with.

## Skill Runtime Components

| Component | Responsibility |
|-----------|----------------|
| `SkillBootstrap` | Spring startup hook; loads classpath skills, example programmatic skills, then user skills from `AgentPaths.SKILLS_DIR` |
| `SkillAutoConfiguration` | `browser4-boot` auto-configuration that registers `SkillBootstrap` eagerly (`@Lazy(false)`) and `SkillService` lazily |
| `Skill` / `AbstractSkill` | Skill contract and lifecycle base class: `onLoad`, `onBeforeExecute`, `onAfterExecute`, `onUnload`, and `validate` |
| `SkillDefinition` | Parsed `SKILL.md` metadata plus `scriptsPath`, `referencesPath`, `assetsPath`, `allowedTools`, `license`, `compatibility`, and `installSteps` |
| `SkillDefinitionLoader` | Scans `classpath*:skills/**/SKILL.md`, parses YAML frontmatter (or the legacy markdown format), and records the `scripts/`, `references/`, and `assets/` paths |
| `DefinitionBackedSkill` | Adapts a `SkillDefinition` with a classpath or filesystem origin into a registerable `Skill`; `skill.run` returns metadata only until a sandboxed executor is supplied |
| `SkillLoader` | Loads one skill or a batch in dependency order and provides reload/unload helpers |
| `SkillComposer` | Builds sequential or parallel composite skills from existing skill IDs |
| `SkillRegistry` | Singleton registry for registration, lookup, discovery, activation, execution, and dependency checks |
| `SkillInstaller` | Document-driven install/uninstall into `AgentPaths.SKILLS_DIR` |
| `SkillService` | `browser4-boot` service over the registry and installer, used by the `/api/skills` endpoints and the MCP management tools; lists, inspects, installs, uninstalls, and reloads skills |
| `SkillController` | `browser4-rest` REST surface under `/api/skills` for list, detail, install (path or zip upload), uninstall, and reload |
| `SkillMCPToolExecutor` | `browser4-rest` MCP management surface over `SkillService`, including `skill.info` and `skill.reload` |
| `SkillExecutionSupport` | Resolves `readReference`/`runScript` paths inside the skill root and rejects absolute, URL, `..`, or escaping paths |
| `SkillToolTarget` | Receiver for `skill.*` calls; owns the `SkillContext` and delegates execution to `SkillRegistry` |
| `SkillToolExecutor` | Exposes the `skill.*` tool domain and enforces the task's access policy before dispatch |
| `SkillScriptToolExecutor` | Implements `skill.readReference` and `skill.runScript`, including the `allowed-tools: python` gate and timeout/output limits |
| `SystemToolExecutor` | Serves the bundled `browser4-cli` documents through `system.skillDoc`, including the reference map, paging, frontmatter helpers, and path-traversal checks |
| `AgentEventBus` | Emits skill lifecycle events through `emitSkillEvent` for server-side observers |

## Creating a New Skill

To create a new skill, follow these steps:

### 1. Create Directory Structure

Create the skill under this directory (or under a directory on the classpath):

```bash
mkdir -p browser4-agentic/src/main/resources/skills/my-skill/{scripts,references,assets}
```

### 2. Create SKILL.md

Create `browser4-agentic/src/main/resources/skills/my-skill/SKILL.md` using the template above.
Keep `name` identical to the directory name.

### 3. Add Implementation (Optional)

For a programmatic skill, add the Kotlin implementation in the appropriate source directory:

```
browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/skills/examples/MySkill.kt
```

Definition-backed skills need no Kotlin code; they are registered directly from `SKILL.md`.

### 4. Add Documentation (Optional)

Create developer guides in `references/` (or `reference/` to match an upstream layout; both are
readable through `skill.readReference`):

```
browser4-agentic/src/main/resources/skills/my-skill/references/developer-guide.md
```

### 5. Add Scripts (Optional)

Create example or helper scripts in `scripts/`:

```
browser4-agentic/src/main/resources/skills/my-skill/scripts/example-usage.kts
browser4-agentic/src/main/resources/skills/my-skill/scripts/my_helper.py
```

Only Python scripts are executable through `skill.runScript`, and only when the skill declares
`allowed-tools: python`.

### 6. Add Assets (Optional)

Add configuration files and templates in `assets/`:

```
browser4-agentic/src/main/resources/skills/my-skill/assets/config.json
browser4-agentic/src/main/resources/skills/my-skill/assets/template.md
```

### 7. Add Tests

Add or extend tests under `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/skills/`. The
suite covers the whole skill lifecycle:

| Test class | Coverage |
|------------|----------|
| `SkillDefinitionLoaderTest` | Bundled `SKILL.md` definitions, metadata, and resource discovery |
| `SkillSpecComplianceTest` | Frontmatter validation rules |
| `SkillTest` | `SkillMetadata`/`SkillContext`/`SkillResult` value objects and `AbstractSkill` lifecycle guards |
| `SkillRegistryTest`, `SkillLoaderTest` | Registration, dependency ordering, reload, and unload |
| `SkillComposerTest` | Sequential and parallel composite execution (declared in `SkillLoaderTest.kt`) |
| `SkillBootstrapTest`, `SkillBootstrapIntegrationTest` | Startup load order and bundled-skill registration |
| `SkillInstallerTest` | Install/uninstall, overwrite, install-step parsing, and documentation reads |
| `SkillProgressiveDisclosureTest` | `skill.list`/`skill.activate` discovery payloads |
| `SkillExecutionToolsTest`, `SkillToolExecutorTest` | Path-traversal rejection, the `allowed-tools` gate, and `skill.run` dispatch |
| `SkillScriptExecutionIntegrationTest`, `SkillToolExecutorScriptSupportTest` | Script tool specifications and Python execution when allowed |
| `examples/ExampleSkillsTest` | The programmatic example skills (`WebScrapingSkill`, `FormFillingSkill`, `DataValidationSkill`) |

Cross-module coverage lives with the surfaces it exercises: `browser4-boot` has `SkillServiceTest`,
`browser4-rest` has `SkillControllerTest`, `SkillMCPToolExecutorPolicyTest`, and
`SkillResultSchemaTest`, and `browser4-tests/browser4-e2e-tests` has `SkillInstallE2ETest` and
`SkillRegistrationAndInvocationE2ETest` (LLM-backed, excluded from normal runs).

## Best Practices

### SKILL.md

- Keep the description concise and clear
- Document all parameters with types and defaults
- Provide multiple usage examples
- Include error handling scenarios
- Link to related skills and documentation

### Scripts

- Make scripts executable and self-contained
- Include comments explaining each step
- Show common use cases
- Demonstrate error handling
- Prefer standard-library-only Python unless dependencies are documented

### References

- Provide detailed technical documentation
- Include architecture diagrams if helpful
- Document extension points
- Add troubleshooting guides

### Assets

- Use JSON for configuration files
- Provide templates for common patterns
- Include example data
- Document asset file formats

## Loading Skills

Programmatic skills can be loaded and registered using the SkillRegistry:

```kotlin
val registry = SkillRegistry.instance
val context = SkillContext(sessionId = "my-session")

// Register a skill
val skill = MyCustomSkill()
registry.register(skill, context)

// Execute a skill
val result = registry.execute(
    skillId = "my-skill",
    context = context,
    params = mapOf("param1" to "value1")
)
```

## Skill Discovery

List lightweight summaries for matching, then activate the selected skill to load its full
documentation:

```kotlin
// Summaries contain id, name, description, version, and tags
val summaries = registry.listSkillSummaries(maxDescriptionChars = 256)

// Activation returns the full SKILL.md plus resource paths
val activation = registry.activateSkill("web-scraping")
```

Find skills by tags or author:

```kotlin
// Find all scraping skills
val scrapingSkills = registry.findByTag("scraping")

// Find skills by author
val mySkills = registry.findByAuthor("My Organization")
```

## Skill Dependencies

Skills can depend on other skills:

```kotlin
override val metadata = SkillMetadata(
    id = "my-skill",
    name = "My Skill",
    dependencies = listOf("web-scraping", "data-validation")
)
```

The SkillLoader handles dependency resolution automatically:

```kotlin
val loader = SkillLoader(registry)
val results = loader.loadAll(
    listOf(MySkill(), WebScrapingSkill()),
    context
)
```

The same dependencies can be declared in `SKILL.md` under `metadata.dependencies`.

## Composite Skills

`SkillComposer` combines registered skills into a single sequential or parallel skill:

```kotlin
val composer = SkillComposer(registry)
val pipeline = composer.sequential("collect-and-validate", listOf("web-scraping", "data-validation"))
val batch = composer.parallel("collect-many", listOf("web-scraping", "weather"))
```

A sequential composite stops at the first failing skill; a parallel composite runs every skill and
reports all failures in the result metadata.

## Testing Skills

Write tests for your skills:

```kotlin
@Test
fun testMySkill() = runBlocking {
    val skill = MyCustomSkill()
    val context = SkillContext(sessionId = "test")

    val result = skill.execute(
        context,
        mapOf("param1" to "value1")
    )

    assertTrue(result.success)
    assertNotNull(result.data)
}
```

## Resources

- [Project Documentation](../../../../../docs/)
- [SKILL.md Audit Methodology](../../../../../docs/skill-audit-methodology.md) — checklist for
  agent-optimized `SKILL.md` files
- [Skill artifact checklist](../../../../../docs-dev/copilot/examples/skill.md) — frontmatter,
  trigger-body, and tool-reference expectations
- [Testing taxonomy](../../../../../docs/TESTING.md) — test tags and scopes used by the skill suite
- Skill source implementations in `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/skills/`
- Skill tests in `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/skills/`
- Test resources in `browser4-tests/pulsar-tests-common/src/main/resources/static/`

---

*Last updated: 2026-11-04*
