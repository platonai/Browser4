---
title: "Plugin Loading"
description: "Use when you need to understand how Browser4 decides which plugins load at startup — default-loaded vs opt-in — and how to override that decision."
tier: catalog
---

# Plugin Loading

## Overview

Every plugin belongs to one of two loading categories, declared by `defaultEnabled` in `META-INF/browser4-plugin.json`. The effective decision is made by `PluginLoadPolicy` at two levels: **classpath** — `PluginClasspathEnhancer` only adds enabled plugins to the classloader before Spring starts (applies to the standalone `plugins/` directory; this is the hard gate); **runtime** — `PluginManager` skips mount wiring / `onStartup` for plugins whose beans reach the Spring context via the JVM classpath (e.g. the bundle's `plugins/*` wildcard). Tools, event handlers, and swarm facades of disabled plugins are not registered.

## Quick Index

| Name | Type | One-line description |
|------|------|----------------------|
| Default-loaded | Category | `defaultEnabled: true` — activated automatically at startup, unless explicitly disabled |
| Opt-in (default-disabled) | Category | `defaultEnabled: false` — not activated at startup, unless explicitly enabled |
| `browser4.plugins.enable` | Property / env var | Comma-separated plugin names to force-enable |
| `browser4.plugins.disable` | Property / env var | Comma-separated plugin names to force-disable |
| `browser4.plugins.enable-all` | Property / env var | `true` → activate every plugin unless explicitly disabled |

## Loading Categories

| Category | `defaultEnabled` | Behavior |
|---|---|---|
| Default-loaded | `true` (default) | Activated automatically at startup, unless explicitly disabled |
| Opt-in (default-disabled) | `false` | **Not** activated at startup, unless explicitly enabled |

`disable` always wins over `enable`. Opt-in plugins still ship in the `plugins/` directory and show up in `plugin list` / `GET /api/plugins` with `defaultEnabled: false, enabled: false` until enabled.

## Override Properties

Explicit overrides via system property, env var, or `application.properties`:

| Property / env var | Effect |
|---|---|
| `browser4.plugins.enable` / `BROWSER4_PLUGINS_ENABLE` | Comma-separated plugin names to force-enable |
| `browser4.plugins.disable` / `BROWSER4_PLUGINS_DISABLE` | Comma-separated plugin names to force-disable |
| `browser4.plugins.enable-all` / `BROWSER4_PLUGINS_ENABLE_ALL` | `true` → activate every plugin unless explicitly disabled |

```bash
# Activate a specific opt-in plugin
browser4-rest --browser4.plugins.enable=browser4-myfeature

# Or enable everything
browser4-rest --browser4.plugins.enable-all=true
```
