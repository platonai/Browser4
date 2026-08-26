---
name: browser4-coding
title: "browser4-coding"
tier: procedure
description: "Create and validate Browser4 plugins, skills, JS scripts, and shell scripts; and develop Browser4 ITSELF (self-development): build modules, extract live skeletons, analyze impact, plan dev tasks, and check CDP pitfalls. Use when the user asks to write a Browser4 plugin or skill, write browser JS or build scripts, modify Browser4's own Kotlin/Rust code, or validate repo consistency."
allowed-tools: coding.scaffold coding.scaffoldFlow coding.scaffoldFromExample coding.validate coding.write coding.read coding.replace coding.replaceRegex coding.editLines coding.insertAfter coding.revert coding.shell coding.mvnBuild coding.ktSymbols coding.ktReferences coding.ktInheritance coding.impact coding.moduleGraph coding.devTask coding.trapCheck coding.protect coding.tokenStats coding.estimateTokens tab.eval tab.console
---

# browser4-coding

## Quick Start

Create and validate a plugin, or a skill:

```text
coding.scaffold(type="plugin", pluginName="My Plugin", domain="media",
                basePackage="ai.platon.pulsar.myplugin",
                toolMethod="detectMedia", toolDescription="Detect media on the page")
coding.validate(type="plugin", path="browser4-plugins/browser4-myplugin")

coding.scaffold(type="skill", name="my-skill", description="What it does")
coding.validate(type="skill", path="skills/my-skill/SKILL.md")
```

For self-development work (modifying Browser4 itself), start with `coding.scaffoldFlow` — see Workflows below.

The programming-agent kernel for Browser4: sandboxed shell + filesystem, artifact scaffolding/validation (plugins, skills, browser JS, scripts), and a full self-development toolset for modifying Browser4's own code (live module graph, impact analysis, Maven builds with structured diagnostics, CDP pitfall awareness, high-level dev-task planning).

## When to Use

- User wants to create a new Browser4 plugin, skill (SKILL.md), browser JS script, or build/deploy/run script
- User wants to validate any of the above
- User wants to modify Browser4's own code (Kotlin modules, Rust CLI, poms) — use the self-development tools below
- User wants to check repo governance (versions, module registration) or the CDP pitfalls before editing the browser driver

## How It Works

The `coding` tools fall into three layers: **artifact tools** generate and validate plugins, skills, JS, and scripts (templates from real code so they never go stale); **self-development tools** inspect and modify Browser4 itself (live module graph, impact analysis, Maven builds, CDP trap checks); and **shell/filesystem tools** provide a sandboxed environment for anything else.

## Patterns

### 1. Create and validate a new plugin

```text
coding.scaffold(type="plugin", pluginName="...", domain="...", basePackage="...", toolMethod="...", toolDescription="...")
coding.validate(type="plugin", path="browser4-plugins/browser4-xxx")
coding.mvnBuild(module="browser4-plugins/browser4-xxx")
```

### 2. Copy an existing plugin's structure

```text
coding.scaffoldFromExample(path="browser4-plugins/browser4-seo")
coding.validate(type="plugin", path="browser4-plugins/browser4-xxx")
```

### 3. Repo governance check before merging

```text
coding.validate(type="repo-consistency")
```

## Flags

`coding` is a tool set, not a CLI — there are no command-line flags. Tool parameters are documented per tool in the sections below.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `coding.validate` fails | Missing/incorrect required parameters | Read the validation output and fix the named field |
| `coding.mvnBuild` fails | Compile errors in generated code | Read the structured diagnostics and fix the faulty file |
| `scaffoldFromExample` renames wrong classes | Ambiguous class stem | Pass explicit per-class keys (e.g. `SeoService=CustomService`) |

## Artifact Tools (plugins / skills / js / scripts)

### `coding.scaffold`
Generate a template for any of the four artifact types.

| Type | Required Parameters | Optional Parameters |
|------|-------------------|---------------------|
| `plugin` | `pluginName`, `domain`, `basePackage`, `toolMethod`, `toolDescription` | `pdkVersion` (defaults to the repo VERSION file) |
| `skill` | `name` (must equal directory name), `description` (1-1024 chars) | `triggers` (comma-separated), `tools` (comma-separated → `allowed-tools`) |
| `js` | `name` | `purpose` (`extract`, `inject`, `interact`) |
| `script` | `name` | `scriptType` (`build`, `deploy`, `run`), `shell` (`ps1`, `bash`) |

### `coding.validate`
Validate an artifact — or the whole repo (`type="repo-consistency"`, no path).

| Type | Path Meaning | What It Checks |
|------|-------------|----------------|
| `plugin` | Plugin base directory | pom.xml (parent browser4-pdk, literal version, required deps), plugin.json (PluginManifest schema), AutoConfiguration implements ToolMount, ToolExecutor structure, manifest.name == artifactId |
| `skill` | SKILL.md file path | YAML frontmatter `name` + `description` (1-1024), name == directory name, allowed-tools, tool-reference cross-check |
| `js` | .js file path | Bracket balance, return statement, use strict, anti-patterns |
| `script` | .ps1/.sh file path | param block, shebang, error handling |
| `repo-consistency` | *(none)* | VERSION format; VERSION == root pom == BOM version; every default `<module>` dir exists; on-disk module dirs registered in the root pom |

### `coding.scaffoldFromExample` — anti-staleness live templates
Generate a skeleton from EXISTING real code instead of hand-written templates (which go stale):
- **Single file**: parameterizes `basePackage`/`className`/`domain`/`toolMethod` into `{placeholders}`; omit rename args to see discovered parameters.
- **Directory (plugin/module)**: extracts a MULTI-FILE skeleton set with cross-file-consistent renames — `basePackage` = common package prefix (per-file `.tools`/`.config` suffixes preserved), `artifactId` from pom.xml (parent BOM excluded), `pluginName` from plugin.json. Renaming `className` (or passing `stem=<NewStem>`) derives sibling classes sharing the detected class stem (Seo → Weather renames SeoToolExecutor **and** SeoAutoConfiguration/SeoService/SeoConfig). Explicit per-class keys (`SeoService=CustomService`) always win.

### `coding.scaffoldFlow` — multi-file dev-flow skeletons
`type`: `b4-cli-command` (commands.rs CommandDef + MCPToolController alias + backend + test), `agent-tool` (ToolExecutor + ToolMount auto-config), `rest-endpoint` (Controller + Service + test), `test-class`, `skill`. All identifiers derive from one `name`; `verify=true` runs the type-appropriate build check.

## Self-Development Tools (modifying Browser4 itself)

### `coding.moduleGraph` — live module graph
Scans the repo's real pom.xml files (module path, artifactId, parent, internal deps) and reports the graph; warns when it drifted from the static snapshot (e.g. browser4-pdk, plugins, test modules). `module=<path>` reports transitive dependents + suggested test commands. This is the anti-staleness answer to a hand-maintained module map.

### `coding.impact(path=...)`
Which module owns a file, which modules are affected transitively (from the LIVE pom graph), and suggested test commands. **Run before modifying Browser4's own code.**

### `coding.mvnBuild(module=..., goals=...)`
`mvn -pl <module> -am <goals>` with structured Kotlin/Java compiler diagnostics instead of raw logs — the fast, dependency-free alternative to a Kotlin LSP server. Use to check code after edits (goals default `compile`, `skipTests=true`).

### `coding.ktSymbols` / `coding.ktReferences` / `coding.ktInheritance`
Zero-dependency Kotlin analysis: symbols (classes/functions/properties), references (`scope='file'` default, or `scope='module'` for a cross-file scan that excludes the declaring file), and the inheritance chain (`ktInheritance(path, className?)` walks the primary supertype across the module's files). Use before refactoring to assess impact without starting a JDTLS server.

### `coding.devTask(task=..., verify=..., runTests=...)`
High-level entry: parse a natural-language dev task into an executable plan following the AGENTS.md flow — locate files → impact → mvnBuild compile → smallest-scope tests → trapCheck (driver code) → repo-consistency → commit guidance. Module mentions resolve against the LIVE pom graph when available. `verify=true` runs the fast checks (compile, trapCheck, repo-consistency); `runTests=true` (with verify) also runs the module's test suite — scoped to `-Dtest=<FooTest>` when the task names a test class. `module=` overrides the inferred module.

### `coding.trapCheck(path=...)`
Scan a file for the three known Browser4 CDP pitfalls (AGENTS.md): mouseWheel race (crbug.com/444929150), cursor positioning after focus+click, Input.insertText racing. **Run before editing browser-driver code** (PulsarWebDriver.kt and friends).

### `coding.protect(path=..., on=...)`
Session-level dynamic file protection: `coding.protect(path="src/Foo.kt", on=true)` blocks delete/replace/editLines/insertAfter on that exact file; `on=false` removes it; `coding.protect()` lists dynamic protections. Repo-governance defaults (VERSION/AGENTS.md/CLAUDE.md/root pom/BOM/CI) are always protected and cannot be unprotected.

## Shell & Filesystem Tools

- `coding.shell(command, timeoutSeconds?, workingDir?)` — execute allowed dev commands (git/cargo/mvn/npm/python/...)
- File ops: `read`, `readLines`, `write`, `append`, `replace`, `replaceRegex`, `editLines`, `insertAfter`, `revert`, `delete`, `mkdir`, `copy`, `move`, `stat`, `diff` (myers/patience)
- Search: `glob`, `grep` (skip excluded dirs), `languages`
- **Repo governance protection**: delete/replace/editLines/insertAfter are BLOCKED for VERSION, AGENTS.md, CLAUDE.md, root pom.xml, browser4-dependencies/pom.xml (BOM), .github/workflows/ci.yml — do not try to work around this; ask the user for explicit intent instead. Add session-level protections with `coding.protect(path, on)`.
- `coding.runCode(language, code)` — sandboxed snippet execution (kotlin/js/python/bash)
- LSP: `diagnostics(path)`, `symbols(pattern?)`, `references(path, symbol)`, `lspServers` — per-language servers (ts/js/py/rs), started on demand, requires the server installed; degrade gracefully when missing
- `coding.tokenStats(reset?)` — per-method token usage of coding tool calls so far (calls/errors/input/output tokens, avg/max output); use to audit context-window consumption. `coding.estimateTokens(text)` — heuristic token count of any text before sending it.

## Workflows

### Creating a Browser4 Plugin

```
1. coding.scaffold(type="plugin", pluginName="browser4-xxx", domain="xxx",
     basePackage="ai.platon.pulsar.xxx", toolMethod="doXxx",
     toolDescription="Does xxx on the page")

2. coding.write(path=<file>, content=<from scaffold>) ... write each file

3. coding.validate(type="plugin", path="browser4-plugins/browser4-xxx") → fix issues

4. coding.mvnBuild(module="browser4-plugins/browser4-xxx") → compile to verify

5. If compile fails: coding.read the error file → coding.replace → recompile
```

### Cloning an existing plugin (anti-staleness)

```
1. coding.scaffoldFromExample(path="browser4-plugins/browser4-seo")
   → Discovery mode: shows basePackage/className/domain/toolMethod/artifactId/stem

2. coding.scaffoldFromExample(path="browser4-plugins/browser4-seo",
     className="WeatherToolExecutor", basePackage="ai.platon.pulsar.weather",
     domain="weather", artifactId="browser4-weather")
   → Multi-file skeleton; sibling classes (WeatherAutoConfiguration/WeatherService)
     follow the stem rename automatically

3. coding.write each generated file → coding.mvnBuild → coding.validate(type="plugin")
```

### Modifying Browser4's own code (recommended order)

```
1. coding.impact(path=<file>)      → owning module + affected modules + test commands
2. coding.read the file            → understand the code
3. coding.trapCheck(path=<file>)   → CDP pitfalls if browser-driver code
4. Make the edit (coding.replace / coding.editLines / coding.write)
5. coding.mvnBuild(module=<module>) → compile check
6. coding.shell(command=<smallest test scope>) → run tests
7. coding.validate(type="repo-consistency") → governance still consistent
8. coding.moduleGraph() → confirm no module-graph drift
```

### Creating a Skill / JS Script / Build Script

```
# Skill
1. coding.scaffold(type="skill", name="my-skill", description="...", triggers="...", tools="coding.read,tab.eval")
2. coding.write(path="skills/my-skill/SKILL.md", content=<from scaffold>)  # dir name MUST equal name
3. coding.validate(type="skill", path="skills/my-skill/SKILL.md")

# Browser JS
1. coding.scaffold(type="js", name="extract-prices", purpose="extract")
2. coding.write(path="scripts/extract-prices.js", content=<from scaffold>)
3. coding.validate(type="js", path="scripts/extract-prices.js")
4. tab.eval(expression=<read the JS file>) → tab.console() → iterate

# Build script
1. coding.scaffold(type="script", name="build", scriptType="build", shell="ps1")
2. coding.write(path="build.ps1", content=<from scaffold>)
3. coding.validate(type="script", path="build.ps1") → coding.shell(command="powershell -File build.ps1")
```

## Notes

- The generated plugin AutoConfiguration implements `ToolMount`, so PluginManager registers the plugin's tools into CustomToolRegistry automatically.
- plugin.json follows the real PluginManifest schema (`name`/`version`/`description`/`dependsOn`/`autoConfigurationClasses`).
- The coding module is intentionally independent of pulsar-common; heavy backends (kotlin-compiler-embeddable, LSP servers) are probed at runtime and NEVER downloaded/loaded by default — tools degrade gracefully when unavailable.
