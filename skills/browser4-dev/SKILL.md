---
name: browser4-dev
title: "browser4-dev"
tier: procedure
description: "Develop features inside the Browser4 repository itself: add CLI commands, agent tool domains, REST endpoints, tests, and skills; analyze impact, plan dev tasks, check CDP pitfalls, and verify builds. Use when the user asks to add a new browser4-cli command, new tool domain, REST endpoint, modify Browser4's own Kotlin/Rust code, or check which modules a change affects."
allowed-tools: coding.scaffoldFlow coding.scaffoldFromExample coding.mvnBuild coding.devTask coding.impact coding.moduleGraph coding.trapCheck coding.protect coding.scaffold coding.write coding.read coding.readLines coding.replace coding.replaceRegex coding.editLines coding.insertAfter coding.revert coding.validate coding.shell coding.ktSymbols coding.ktReferences coding.ktInheritance coding.diagnostics coding.references coding.symbols coding.tokenStats coding.estimateTokens
---

# browser4-dev

## Quick Start

Add a new `browser4-cli` command with one call:

```text
coding.scaffoldFlow(type="b4-cli-command", name="my-command", verify=true)
```

`scaffoldFlow` generates the whole chain — `commands.rs` CommandDef, backend MCP alias, tool executor, and test — from a single name; `verify=true` runs the type-appropriate build check. For other work (agent tool, REST endpoint, test class, skill), use the matching `type` and see Workflows below.

Develop features inside the Browser4 repository itself, following the repository's
own conventions (AGENTS.md) — with multi-file skeletons generated from the
repository's real reference implementations, so the output never goes stale.

## When to Use

- User wants to add a new `browser4-cli` command
- User wants to add a new agent tool domain (plugin tool)
- User wants to add a REST endpoint (Controller + Service)
- User wants to add a test class or a skill
- User wants to modify Browser4's own Kotlin/Rust code and verify it compiles
- User wants to know which modules a change affects, or plan a whole dev task

## How It Works

`scaffoldFlow` derives multi-file skeletons from the repository's real reference implementations, so generated code matches current conventions instead of stale templates. `moduleGraph`/`impact` rebuild the dependency picture from the live `pom.xml` files, and `mvnBuild` verifies with structured diagnostics. Follow AGENTS.md conventions at every step.

## Patterns

### 1. Add a new CLI command

```text
coding.scaffoldFlow(type="b4-cli-command", name="my-command", verify=true)
coding.mvnBuild(module="browser4-rest")
```

### 2. Add a REST endpoint

```text
coding.scaffoldFlow(type="rest-endpoint", name="my-resource", verify=true)
```

### 3. Modify browser driver code

```text
coding.trapCheck(path="browser4-core/browser4-browser/src/.../PulsarWebDriver.kt")
coding.impact(path=<file>)
coding.mvnBuild(module="browser4-core/browser4-browser")
```

## Flags

`coding` tools take structured arguments, not CLI flags — see Workflows below for each tool's parameters.

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `mvnBuild` fails after scaffolding | Generated code conflicts with existing code | Read diagnostics, fix the conflicting file, re-run |
| `scaffoldFlow` rejects `verify=true` | Type-specific build check failed | Run without verify, inspect, then re-run after fixing |
| Unknown blast radius | Skipped `moduleGraph`/`impact` | Run them first — see Workflow 0 |

## Workflows

### 0. Before changing anything — know the blast radius

```
coding.moduleGraph(module="browser4-core/browser4-browser")
  → LIVE module graph rebuilt from the real pom.xml files: direct deps,
    transitive dependents, and drift warnings vs the static snapshot

coding.impact(path="browser4-core/browser4-browser/src/.../PulsarWebDriver.kt")
  → owning module + affected modules (transitively) + suggested test commands

coding.trapCheck(path=<browser-driver file>)
  → CDP pitfall advisories (mouseWheel race, cursor positioning, insertText racing)
```

### 1. Add a new CLI command

```
coding.scaffoldFlow(type="b4-cli-command", name="extract-prices",
     description="Extract product prices from a page", category="Extract")
  → 4 files: commands.rs CommandDef, MCPToolController alias, backend skeleton, test

coding.write(path="cli/browser4-cli/src/commands.rs", content=<CommandDef block>)
coding.write(path="browser4-rest/.../MCPToolController.kt", content=<alias snippet>)
... write each piece ...

# Verify both sides:
coding.shell(command="cargo test --bin browser4-cli")          # Rust side
coding.mvnBuild(module="browser4-rest", goals="compile")       # Kotlin side
```

### 2. Add a new agent tool domain (plugin)

```
coding.scaffoldFlow(type="agent-tool", name="browser4-weather", domain="weather",
     basePackage="ai.platon.pulsar.weather", toolMethod="fetchWeather",
     description="Fetch weather for the current page")
  → 2 files: ToolExecutor + ToolMount auto-config

coding.mvnBuild(module="browser4-plugins/browser4-weather", goals="compile")
coding.validate(type="plugin", path="browser4-plugins/browser4-weather")
```

### 3. Add a REST endpoint

```
coding.scaffoldFlow(type="rest-endpoint", name="analysis",
     description="Run an analysis task")
  → 3 files: AnalysisController + AnalysisService + AnalysisControllerTest
```

### 4. Add a test class / skill

```
coding.scaffoldFlow(type="test-class", name="my-tool",
     basePackage="ai.platon.pulsar.agentic.tools")
coding.scaffoldFlow(type="skill", name="browser4-myflow",
     description="A custom Browser4 flow")
```

### 5. Clone an existing implementation (anti-staleness)

```
# Single file:
coding.scaffoldFromExample(path="browser4-plugins/browser4-seo/.../SeoToolExecutor.kt")
  → discovery: shows parameters (basePackage, className, domain, toolMethod)
coding.scaffoldFromExample(path=<same>, basePackage="ai.platon.pulsar.weather",
     className="WeatherToolExecutor", domain="weather", toolMethod="fetchWeather")

# Whole plugin directory (cross-file consistent + stem-derived siblings):
coding.scaffoldFromExample(path="browser4-plugins/browser4-seo",
     className="WeatherToolExecutor", basePackage="ai.platon.pulsar.weather",
     domain="weather", artifactId="browser4-weather")
  → renames the executor AND sibling classes sharing the stem
    (SeoAutoConfiguration→WeatherAutoConfiguration, SeoService→WeatherService, ...)
```

### 6. Plan a whole dev task in one call

```
coding.devTask(task="fix the mouseWheel race in PulsarWebDriver.kt under
     browser4-core/browser4-browser/src/main/kotlin, add a regression test",
     verify=true)
  → AGENTS.md dev-flow plan (read → impact → mvnBuild → tests → trapCheck →
    repo-consistency → commit), with the fast checks already RUN:
    compile of the inferred module, CDP trap check, repo-consistency.
    Add runTests=true to also execute the module's test suite.
```

## Rules

1. **Prefer scaffoldFlow over hand-writing** — multi-file consistency
   (same identifier in commands.rs + alias + test) is automatic.
2. **Verify both sides** for CLI changes: `cargo test --bin browser4-cli` for
   Rust, `coding.mvnBuild` for Kotlin.
3. **Kotlin diagnostics**: use `coding.mvnBuild` (fast compiler passthrough) —
   no JDTLS server is configured.
4. **AGENTS.md is the contract** — when the repo's conventions change, regenerate
   skeletons from the updated reference implementations rather than editing
   stale output.
5. **Keep changes small**: one command/domain/endpoint per cycle; `coding.revert`
   undoes a bad edit.
6. **Governance files are protected**: delete/replace on VERSION, AGENTS.md,
   CLAUDE.md, root pom.xml, the BOM, and the CI workflow are blocked by design —
   ask the user for explicit intent instead of working around it. Add
   session-level protections for any other file with `coding.protect(path, on)`.
7. **Module topology is live**: `coding.moduleGraph`/`coding.impact` rebuild from
   the real poms; the static ModuleMap snapshot is guarded by E2E tests against
   drift — sync it when adding a module. Use `coding.ktReferences(scope="module")`
   and `coding.ktInheritance` before refactoring shared Kotlin symbols.

## Examples

```
User: 给 browser4-cli 加一个 extract-prices 命令
Agent: 1. coding.scaffoldFlow(type="b4-cli-command", name="extract-prices", ...)
       2. 写 commands.rs CommandDef、MCPToolController alias、后端骨架、测试
       3. coding.shell(command="cargo test --bin browser4-cli")
       4. coding.mvnBuild(module="browser4-rest", goals="compile")
       5. 报告：6 处触点全部就位，双端编译通过

User: 克隆 browser4-seo 插件成天气插件
Agent: 1. coding.scaffoldFromExample(path="browser4-plugins/browser4-seo",
          className="WeatherToolExecutor", basePackage="ai.platon.pulsar.weather",
          domain="weather", artifactId="browser4-weather")
       2. 逐文件 coding.write
       3. coding.mvnBuild(module="browser4-plugins/browser4-weather")
       4. coding.validate(type="plugin", path="browser4-plugins/browser4-weather")
```
