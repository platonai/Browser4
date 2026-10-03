---
title: "Contributing a `htmlsnapshot summary` Algorithm"
description: "Use when adding a page summary algorithm to a Browser4 plugin — implement the PageSummaryAlgorithm SPI, mount it, and make it selectable with --algorithm."
tier: procedure
---

# Contributing a `htmlsnapshot summary` Algorithm

The `htmlsnapshot summary` command summarizes a **fresh snapshot of the active
page** through a small algorithm SPI. The built-in algorithm is the Web Page
Summary Index (`wpsi`); a plugin can register additional algorithms selected
with `--algorithm <id>` (MCP tool `html_snapshot_summary`, argument
`algorithm`).

This guide covers the algorithm mount point only. For plugin project layout,
packaging, the `plugins/` directory, and SDK compatibility, start from
[workflow.md](workflow.md) and model the project on
`browser4-plugins/browser4-markdown/`.

## Quick Start

An algorithm is four files plus a build — the steps below take them one at a time:

| File | What it holds | Step |
|---|---|---|
| `pom.xml` | Parent `browser4-pdk`; `browser4-skeleton` in `provided` scope | 1 |
| `…/summary/<Name>SummaryAlgorithm.kt` | The `PageSummaryAlgorithm` implementation | 2 |
| `…/config/<Name>AutoConfiguration.kt` | The `PageSummaryAlgorithmMount` bean, named in `AutoConfiguration.imports` | 3 |
| `META-INF/browser4-plugin.json` | Plugin name, version, SDK constraint | 4 |

Then package, deploy into `$BROWSER4_HOME/plugins/`, restart, and verify that
`htmlsnapshot algorithms` lists the id (step 5).

## When to Use

Reach for the SPI when the built-in `wpsi` outline is the wrong shape — you want
article-focused plain text, a domain-specific digest, or JSON a downstream agent
can parse — and the result must be selectable by every session with
`htmlsnapshot summary --algorithm <id>`.

For a single page, don't: `htmlsnapshot get` / `htmlsnapshot query` plus your own
post-processing needs no plugin. The SPI is for algorithms that ship with the
server and are reused across sessions.

## How It Works

```
plugin JAR
  @AutoConfiguration class : PageSummaryAlgorithmMount
      getPageSummaryAlgorithms() -> List<PageSummaryAlgorithm>
                 │  (collected at startup by PluginManager in browser4-boot)
                 ▼
PageSummaryAlgorithmRegistry (singleton in browser4-skeleton)
                 │  html_snapshot_summary(algorithm = "<id>")
                 ▼
             algorithm.generate(PageSummaryInput) : String
```

Key types (package `ai.platon.pulsar.skeleton.workflow.parse.html`,
module `browser4-skeleton`):

| Type | Role |
|---|---|
| `PageSummaryAlgorithm` | The SPI: `id`, `displayName`, `description`, optional `version`, `builtin` (leave default `false`), and `generate(input)` |
| `PageSummaryInput` | `data class PageSummaryInput(document: FeaturedDocument, pageUrl: String, title: String)` — a fresh, read-only DOM plus page metadata |
| `PageSummaryAlgorithmRegistry` | Process-wide registry. `wpsi` is pre-registered as the default; duplicate ids are **first-wins** (the loser logs a warning) |
| `PageSummaryAlgorithmMount` | The `PluginMount` interface (`ai.platon.pulsar.skeleton.plugin`) a plugin bean implements to contribute algorithms |

Registry semantics worth knowing:

- `id` must match `[a-z0-9][a-z0-9-]*`; an illegal id throws at registration.
- The id is stable user-facing API (CLI option, error messages, JSON output).
  Renaming it is a breaking change.
- A missing/blank `algorithm` argument resolves to the default (`wpsi`).
  An unknown id fails with an error message listing every available id — the
  same list returned by `htmlsnapshot algorithms`
  (`html_snapshot_algorithms`).
- Only the built-in `wpsi` output gets the CLI's compact outline rendering;
  output of other algorithms is printed verbatim (still saved to a file).

## Step 1 — Depend on `browser4-skeleton`

In the plugin POM (parent `browser4-pdk`), all Browser4/Spring/Kotlin
dependencies stay `provided` — the server supplies them at runtime:

```xml
<parent>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-pdk</artifactId>
    <version>4.14.0-rc.6</version>
</parent>

<dependencies>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-skeleton</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## Step 2 — Implement `PageSummaryAlgorithm`

```kotlin
package ai.platon.pulsar.myplugin.summary

import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryInput

class ReadabilitySummaryAlgorithm : PageSummaryAlgorithm {
    override val id = "readability"
    override val displayName = "Readability Summary"
    override val description = "Article-focused plain-text summary."
    override val version = "1.0.0"
    // builtin stays false — only core algorithms return true.

    override fun generate(input: PageSummaryInput): String {
        // input.document is the FRESH snapshot DOM (Jsoup FeaturedDocument).
        // Treat it as read-only.
        val doc = input.document
        return buildString {
            appendLine("url: ${input.pageUrl}")
            appendLine("title: ${input.title}")
            // ... summarize doc ...
        }
    }
}
```

Contract:

- Return YAML, JSON, or plain text — whatever self-contained format fits the
  use case. The string goes back over MCP verbatim and is saved to a file by
  the CLI.
- Implementations must be **thread-safe**; concurrent MCP calls on different
  sessions may invoke `generate` simultaneously.
- Do not perform network access or long blocking work inside `generate`
  without your own dispatcher; the input already contains the complete fresh
  document.

## Step 3 — Expose it through a `PageSummaryAlgorithmMount` bean

```kotlin
package ai.platon.pulsar.myplugin.config

import ai.platon.pulsar.myplugin.summary.ReadabilitySummaryAlgorithm
import ai.platon.pulsar.skeleton.plugin.PageSummaryAlgorithmMount
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Lazy

@AutoConfiguration
@ConditionalOnProperty(name = "myplugin.summary.enabled", havingValue = "true", matchIfMissing = true)
@Lazy
class MySummaryAutoConfiguration : PageSummaryAlgorithmMount {

    override fun getPageSummaryAlgorithms(): List<PageSummaryAlgorithm> =
        listOf(ReadabilitySummaryAlgorithm())
}
```

Register the auto-configuration class in
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
ai.platon.pulsar.myplugin.config.MySummaryAutoConfiguration
```

`PluginManager` discovers every `PluginMount` bean at application startup and
registers the contributed algorithms through the same disabled-plugin /
SDK-compatibility gates as every other mount point — no extra wiring needed.

## Step 4 — Add the plugin manifest

`src/main/resources/META-INF/browser4-plugin.json`:

```json
{
  "name": "browser4-my-summary",
  "version": "1.0.0",
  "sdkVersion": "${project.version}",
  "description": "Adds a readability-focused htmlsnapshot summary algorithm",
  "dependsOn": ["browser4-skeleton"],
  "autoConfigurationClasses": ["ai.platon.pulsar.myplugin.config.MySummaryAutoConfiguration"]
}
```

## Step 5 — Build, deploy, verify

```bash
mvn -q -pl browser4-plugins/browser4-my-summary package
cp target/browser4-my-summary-*.jar "$BROWSER4_HOME/plugins/"
# restart the server, then:
browser4-cli htmlsnapshot algorithms                 # the new id is listed
browser4-cli htmlsnapshot summary --algorithm readability
```

The startup log also records the registration:
`Registered page summary algorithm: 'readability' (...)`.

## Patterns

**Delegate to the built-in.** `WpsiPageSummaryAlgorithm` is an object, so your
algorithm can keep the WPSI skeleton and add to it:

```kotlin
override fun generate(input: PageSummaryInput): String = buildString {
    append(WpsiPageSummaryAlgorithm.generate(input))   // the WPSI skeleton
    appendLine()
    appendLine("readability:")
    appendLine("  words: ${countWords(input.document)}")   // your own helper
}
```

**Return machine-readable output.** The string travels back over MCP verbatim
and is saved to a file by the CLI, so JSON or YAML costs nothing extra — only
the built-in `wpsi` output is rendered as a compact outline.

**Ship several algorithms at once.** `getPageSummaryAlgorithms()` returns a
list, so one plugin can register a family of formats and let `--algorithm`
choose between them.

## Flags & Default Selection

Clients must normally opt in with `--algorithm <id>`. To make your algorithm
the server-wide default — so plain `htmlsnapshot summary` uses it — set the
Spring property:

```properties
# application.properties (or -Dbrowser4.htmlsnapshot.summary.algorithm=readability)
browser4.htmlsnapshot.summary.algorithm=readability
```

`PluginManager` applies this **after** all plugin mounts are wired, so
plugin-contributed ids are selectable. Semantics:

- The property names an algorithm **id**; it never replaces the `wpsi`
  registration itself — `--algorithm wpsi` still selects the built-in.
- If the id is not registered at startup (typo, plugin not installed), summary
  calls without an explicit `algorithm` **fail fast** with an error listing the
  available ids, and a warning is logged at startup.
- `htmlsnapshot algorithms` marks the configured id as `"default": true`.
- Non-WPSI output is printed verbatim by the CLI (the full content is still
  saved to a file); there is nothing extra to implement for that.


## Testing

- Unit-test `generate` against Jsoup-parsed fixture HTML; there is no browser
  or Spring dependency in the algorithm itself.
- The registry is a global singleton (`PageSummaryAlgorithmRegistry.instance`):
  in tests, `clear()` it in `@BeforeEach`/`@AfterEach` and re-register the
  built-in `WpsiPageSummaryAlgorithm` to keep tests isolated.
- Verify failure behavior: selecting an unknown id must surface an error whose
  message contains the available id list.

## Errors & Recovery

| Symptom | Cause | Fix |
|---|---|---|
| Startup throws while registering | the `id` does not match `[a-z0-9][a-z0-9-]*` | rename the id — it is validated at registration |
| A startup warning names your id as a duplicate | first-wins: `wpsi` or an earlier plugin already registered that id | pick a distinct id, or drop the other plugin |
| `--algorithm <id>` fails with the list of available ids | the algorithm was never registered | check the JAR is in `plugins/`, the plugin is enabled and SDK-compatible, and the log line `Registered page summary algorithm: '<id>'` is present |
| Plain `htmlsnapshot summary` ignores your algorithm | the server-wide default is unset, or names another id | set `browser4.htmlsnapshot.summary.algorithm=<id>`; an unknown value fails fast with the available-id list |
| The mount bean never runs | the auto-configuration class is missing from `AutoConfiguration.imports`, or `@ConditionalOnProperty` excluded it | add the import line, and check the property value |
