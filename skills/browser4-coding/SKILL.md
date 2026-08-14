---
name: browser4-coding
description: "Create and validate Browser4 plugins, skills, JS scripts, and shell scripts. Use when the user asks to write a Browser4 plugin, create a skill (SKILL.md), write JavaScript for browser execution, or write a build/deploy/run script."
allowed-tools: coding.scaffold coding.write coding.read coding.replace coding.validate coding.shell tab.eval tab.console
---

# browser4-coding

Create and validate Browser4's four core artifact types: plugins, skills, browser JS, and shell scripts.

## When to Use

- User wants to create a new Browser4 plugin
- User wants to write or modify a skill (SKILL.md)
- User wants to write JavaScript for browser execution
- User wants to write a build/deploy/run script (PS1 or Bash)
- User wants to validate any of the above

## Available Tools

### `coding.scaffold`
Generate a template for any of the four artifact types.

| Type | Required Parameters | Optional Parameters |
|------|-------------------|---------------------|
| `plugin` | `pluginName`, `domain`, `basePackage`, `toolMethod`, `toolDescription` | `pdkVersion` (defaults to the repo VERSION file) |
| `skill` | `name` (must equal directory name), `description` (1-1024 chars) | `triggers` (comma-separated), `tools` (comma-separated → `allowed-tools`) |
| `js` | `name` | `purpose` (`extract`, `inject`, `interact`) |
| `script` | `name` | `scriptType` (`build`, `deploy`, `run`), `shell` (`ps1`, `bash`) |

### `coding.validate`
Validate an existing artifact file or directory.

| Type | Path Meaning | What It Checks |
|------|-------------|----------------|
| `plugin` | Plugin base directory | pom.xml (parent browser4-pdk, literal version, required deps), plugin.json (PluginManifest schema: name/version/description/dependsOn/autoConfigurationClasses), AutoConfiguration implements ToolMount, ToolExecutor structure |
| `skill` | SKILL.md file path | YAML frontmatter with `name` + `description` (1-1024), name == directory name, allowed-tools |
| `js` | .js file path | Bracket balance, return statement, use strict, anti-patterns |
| `script` | .ps1 or .sh file path | Shell-specific checks (param block, shebang, error handling) |

## Workflows

### Creating a Browser4 Plugin

```
1. coding.scaffold(type="plugin", pluginName="browser4-xxx", domain="xxx",
     basePackage="ai.platon.pulsar.xxx", toolMethod="doXxx",
     toolDescription="Does xxx on the page")
   → Returns all file templates (7 files, incl. plugin.json + AutoConfiguration.imports)

2. coding.write(path="browser4-plugins/browser4-xxx/pom.xml", content=<from scaffold>)
   coding.write(path="browser4-plugins/browser4-xxx/src/...", content=<from scaffold>)
   ... (write each file)

3. coding.validate(type="plugin", path="browser4-plugins/browser4-xxx")
   → Returns validation issues (fix any errors)

4. coding.shell(command="mvn -pl browser4-plugins/browser4-xxx -am compile -DskipTests")
   → Compile to verify

5. If compile fails: coding.read the error file → coding.replace to fix → recompile
```

Note: the generated AutoConfiguration implements `ToolMount` (returns the tool executor),
so PluginManager registers the plugin's tools into CustomToolRegistry automatically.
plugin.json follows the real PluginManifest schema (`name`/`version`/`description`/`dependsOn`/`autoConfigurationClasses`).

### Creating a Skill

```
1. coding.scaffold(type="skill", name="my-skill", description="Does something useful",
     triggers="When the user asks to do X,When X is needed",
     tools="coding.read,coding.write,tab.eval")

2. coding.write(path="skills/my-skill/SKILL.md", content=<from scaffold>)
   → IMPORTANT: the directory name must equal `name` (loader requirement)

3. coding.validate(type="skill", path="skills/my-skill/SKILL.md")
   → Checks name==directory, description 1-1024, allowed-tools

4. Edit the skill body as needed
```

### Creating a Browser JS Script

```
1. coding.scaffold(type="js", name="extract-prices", purpose="extract")

2. coding.write(path="scripts/extract-prices.js", content=<from scaffold>)

3. coding.validate(type="js", path="scripts/extract-prices.js")
   → Check bracket balance and structure

4. tab.eval(expression=<read the JS file>)
   → Runtime test in the browser

5. tab.console() → Check for errors

6. If errors: coding.replace to fix → tab.eval again → iterate
```

### Creating a Build Script

```
1. coding.scaffold(type="script", name="build", scriptType="build", shell="ps1")

2. coding.write(path="build.ps1", content=<from scaffold>)

3. coding.validate(type="script", path="build.ps1")
   → Check for param block and error handling

4. coding.shell(command="powershell -File build.ps1")
   → Run and verify
```
