---
name: browser4-dev
description: "Develop features inside the Browser4 repository itself: add CLI commands, agent tool domains, REST endpoints, tests, and skills using coding.scaffoldFlow / coding.scaffoldFromExample / coding.mvnBuild. Use when the user asks to add a new browser4-cli command, new tool domain, REST endpoint, or to modify Browser4's own code."
allowed-tools: coding.scaffoldFlow coding.scaffoldFromExample coding.mvnBuild coding.scaffold coding.write coding.read coding.readLines coding.replace coding.replaceRegex coding.editLines coding.insertAfter coding.revert coding.validate coding.shell coding.diagnostics coding.references coding.symbols
---

# browser4-dev

Develop features inside the Browser4 repository itself, following the repository's
own conventions (AGENTS.md) — with multi-file skeletons generated from the
repository's real reference implementations, so the output never goes stale.

## When to Use

- User wants to add a new `browser4-cli` command
- User wants to add a new agent tool domain (plugin tool)
- User wants to add a REST endpoint (Controller + Service)
- User wants to add a test class or a skill
- User wants to modify Browser4's own Kotlin/Rust code and verify it compiles

## Workflows

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

### 2. Add a new agent tool domain

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
coding.scaffoldFromExample(path="browser4-plugins/browser4-seo/.../SeoToolExecutor.kt")
  → discovery: shows parameters (basePackage, className, domain, toolMethod)
coding.scaffoldFromExample(path=<same>, basePackage="ai.platon.pulsar.weather",
     className="WeatherToolExecutor", domain="weather", toolMethod="fetchWeather")
  → instantiates a renamed skeleton from the real reference code
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

## Examples

```
User: 给 browser4-cli 加一个 extract-prices 命令
Agent: 1. coding.scaffoldFlow(type="b4-cli-command", name="extract-prices", ...)
       2. 写 commands.rs CommandDef、MCPToolController alias、后端骨架、测试
       3. coding.shell(command="cargo test --bin browser4-cli")
       4. coding.mvnBuild(module="browser4-rest", goals="compile")
       5. 报告：6 处触点全部就位，双端编译通过
```
