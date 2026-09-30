---
title: "File Reference"
description: "Use when you need to know which files a Browser4 plugin project contains, which are required, and what each file is for."
tier: catalog
---

# File Reference

## Overview

A Browser4 plugin project is a thin Maven JAR: your own code plus true third-party libraries, with all `browser4-*` and Spring Boot dependencies in `provided` scope. This catalog lists every file the archetype scaffolds — required or optional — and the role each plays. For the build walkthrough see [Step-by-Step Workflow](workflow.md).

## Quick Index

| File | Category | One-line purpose |
|------|----------|------------------|
| `pom.xml` | Build | Maven build with `browser4-pdk` parent |
| `META-INF/browser4-plugin.json` | Manifest | Plugin metadata: name, version, deps, auto-configuration classes |
| `META-INF/spring/…AutoConfiguration.imports` | Spring wiring | Registers the auto-configuration class |
| `config/<Feature>AutoConfiguration.kt` | Spring wiring | `@AutoConfiguration` implementing the mount interfaces |
| `config/<Feature>Config.kt` | Configuration | Tunable settings data class |
| `integration/<Feature>BrowseEventHandler.kt` | Event handling | Browse-phase event handlers |
| `integration/<Feature>LoadEventHandler.kt` | Event handling | Load-phase event handlers |
| `service/<Feature>Service.kt` | Business logic | Service injected into handlers and tool executors |
| `tools/<Feature>ToolExecutor.kt` | Agent tools | LLM tool extending `AbstractToolExecutor` |
| `<Feature>Plugin.kt` | Lifecycle | `Browser4Plugin` with `onStartup`/`onShutdown` |
| `README.md` | Docs | Plugin documentation |

## File Details

| File | Required | Purpose |
|------|----------|---------|
| `pom.xml` | Yes | Maven build with `browser4-pdk` parent; all Browser4 deps in `provided` scope |
| `src/main/resources/META-INF/browser4-plugin.json` | Yes | Plugin manifest: name, version, description, dependsOn, defaultEnabled, autoConfigurationClasses |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Yes | Single line: FQN of the `@AutoConfiguration` class |
| `config/<Feature>AutoConfiguration.kt` | Yes | Spring `@AutoConfiguration` — implements `PluginMount` sub-interfaces and defines beans |
| `config/<Feature>Config.kt` | Common | Configuration data class read from Properties/Config |
| `integration/<Feature>BrowseEventHandler.kt` | Common | Browse-phase event handler with business logic for each hook |
| `integration/<Feature>LoadEventHandler.kt` | Optional | Load-phase event handler for URL/parsing hooks |
| `service/<Feature>Service.kt` | Common | Business logic service (injected into event handlers and tool executors) |
| `tools/<Feature>ToolExecutor.kt` | Optional | LLM agent tool extending `AbstractToolExecutor` |
| `<Feature>Plugin.kt` | Optional | `Browser4Plugin` lifecycle (manifest + onStartup/onShutdown) |
| `README.md` | Common | Plugin documentation |

Two files are mandatory in every plugin JAR: the manifest `browser4-plugin.json` (always required for JAR discovery; `defaultEnabled` decides the loading category — see [Plugin Loading](plugin-loading.md)) and the Spring `AutoConfiguration.imports` file (single line: the FQN of the `@AutoConfiguration` class, which implements all chosen `PluginMount` interfaces).
