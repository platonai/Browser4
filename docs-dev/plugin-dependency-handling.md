# Browser4 Plugin Dependency Handling

How Browser4 plugins resolve dependencies at compile time, at runtime, and when
versions conflict.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Case 1: Dependency Already on the Bundle Classpath](#case-1-dependency-already-on-the-bundle-classpath)
3. [Case 2: Dependency NOT on the Bundle Classpath](#case-2-dependency-not-on-the-bundle-classpath)
4. [Case 3: Dependency Version Conflicts](#case-3-dependency-version-conflicts)
5. [Case 4: How the Plugin Mechanism Loads Dependencies](#case-4-how-the-plugin-mechanism-loads-dependencies)
6. [Best Practices](#best-practices)
7. [Quick Reference](#quick-reference)

---

## Architecture Overview

### Key components

| Component | Role |
|---|---|
| **`browser4-pdk`** (parent POM) | Standalone parent for plugin projects — Kotlin + JVM 17 compiler config, imports `browser4-pdk-bom` |
| **`browser4-pdk-bom`** (BOM) | Manages versions for all Browser4 API artifacts, Spring Boot, Kotlin, and Pulsar dependencies |
| **`browser4-bundle`** | The runtime host application — its classpath is the *parent* of the plugin classloader |
| **`PluginClasspathEnhancer`** | Scans `plugins/` for `.jar` files, builds a `URLClassLoader` before Spring Boot starts |
| **`PluginManager`** | Discovers `PluginMount` beans after context refresh and wires them into integration points |

### Classloader hierarchy

```
URLClassLoader(plugin1.jar, plugin2.jar, ...)    ← plugin JARs only
        │
        ▼
Thread-context ClassLoader (parent)              ← the bundle's full classpath
        │
        ▼
Application ClassLoader / Bootstrap              ← JVM
```

This is **parent-first delegation**: when a plugin class references a type, the
JVM asks the parent classloader first. If the type is found there, that version
is used. The plugin's JAR is searched only if the parent does not have it.

---

## Case 1: Dependency Already on the Bundle Classpath

**This is the normal case for most Browser4 plugins.** The bundle already
contains every framework and API dependency a typical plugin needs.

### Which dependencies are on the bundle's classpath?

The `browser4-bundle` module pulls in:

- **Browser4 API**: `browser4-skeleton`, `browser4-browser`, `browser4-common`,
  `browser4-protocol`, `browser4-agentic`, `browser4-agent-tools`, `browser4-boot`,
  `browser4-rest`
- **Spring Boot**: `spring-boot-starter-web`, `spring-boot-starter-jetty`,
  `spring-boot-starter-actuator`, `spring-boot-autoconfigure`
- **Kotlin**: `kotlin-stdlib`, `kotlin-reflect`, `kotlinx-coroutines-core`
- **Third-party** (via transitive deps): OkHttp, Jackson, Commons IO, Ktor, etc.

### What the plugin POM should look like

Use `<scope>provided</scope>` for every dependency that exists on the bundle
classpath. This is the pattern used by `browser4-plugin-archetype`:

```xml
<dependencies>
    <!-- Browser4 API — provided by the host bundle -->
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-skeleton</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-browser</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-common</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-protocol</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>browser4-agentic</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- Spring Boot — provided by the host -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
        <scope>provided</scope>
    </dependency>

    <!-- Kotlin — provided by the host -->
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib</artifactId>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.jetbrains.kotlinx</groupId>
        <artifactId>kotlinx-coroutines-core</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Why `provided` and not `compile`?

| Scope | Compile classpath | Runtime classpath | Transitive to consumers | Plugin JAR size |
|---|---|---|---|---|
| `compile` (default) | ✅ | ✅ | ✅ (leaks to dependents) | Thin (but deps travel transitively) |
| `provided` | ✅ | ❌ (host provides) | ❌ | Thin |

With `provided`:
- **Maven compiles** the plugin successfully (deps are on the compile classpath).
- **The plugin JAR is thin** — only the plugin's own `.class` files.
- **No transitive leakage** — if someone depends on your plugin as a library,
  they don't accidentally pull in `browser4-skeleton` and Spring Boot.
- **At runtime**, the parent classloader resolves these classes from the bundle.

> **⚠️ `browser4-images` currently uses `compile` scope for all its
> dependencies** (`browser4-images/pom.xml`). This works inside the monorepo
> build because everything is on the reactor classpath, but it should be
> corrected to `provided` to match the archetype pattern and prevent transitive
> leakage.

---

## Case 2: Dependency NOT on the Bundle Classpath

When a plugin needs a library that the bundle does **not** provide (e.g.,
`org.apache.pdfbox:pdfbox`, `com.google.zxing:core`), you have two options.

### Option A: Fat JAR with shading (recommended)

Use `maven-shade-plugin` to embed the dependency inside the plugin JAR, with
package relocation to avoid classpath conflicts:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.6.1</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>shade</goal>
                    </goals>
                    <configuration>
                        <artifactSet>
                            <includes>
                                <!-- Only shade the specific deps not on the bundle classpath -->
                                <include>com.google.zxing:core</include>
                                <include>com.google.zxing:javase</include>
                            </includes>
                        </artifactSet>
                        <relocations>
                            <relocation>
                                <pattern>com.google.zxing</pattern>
                                <shadedPattern>com.example.myplugin.shaded.zxing</shadedPattern>
                            </relocation>
                        </relocations>
                        <!-- Keep provided deps out of the shaded JAR -->
                        <filters>
                            <filter>
                                <artifact>*:*</artifact>
                                <excludes>
                                    <exclude>META-INF/*.SF</exclude>
                                    <exclude>META-INF/*.DSA</exclude>
                                    <exclude>META-INF/*.RSA</exclude>
                                </excludes>
                            </filter>
                        </filters>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Relocation is mandatory** if the library might also exist on the bundle
classpath (even transitively). Without relocation the parent classloader finds
the bundle's copy first, and your plugin's shaded copy is never loaded.

### Option B: Bundled JARs in `plugins/` (not recommended)

Placing dependency JARs alongside the plugin JAR in `plugins/`:

```
plugins/
├── my-plugin.jar
├── pdfbox-3.0.0.jar      ← PluginClasspathEnhancer picks this up
└── fontbox-3.0.0.jar     ← ...and this too
```

**Problems with this approach:**
- `PluginClasspathEnhancer` treats every `.jar` in `plugins/` as a plugin. The
  dependency JARs don't have `browser4-plugin.json` manifests, causing warning
  logs and potential `PluginService` errors.
- Ordering is brittle — no guarantee `pdfbox` loads before `my-plugin` unless
  filenames sort correctly.
- Version conflicts with the bundle are unresolved (parent classloader wins).

**Use shading (Option A) instead.**

---

## Case 3: Dependency Version Conflicts

A version conflict occurs when the plugin was compiled against version X of a
library, but the bundle provides version Y at runtime.

### How it happens

```
Plugin compiled against: okhttp 5.0.0  (new API, new methods)
Bundle classpath has:    okhttp 4.12.0 (old API)

→ Plugin calls OkHttpClient.Builder().someNewMethod()
→ Class loaded from bundle (parent-first)
→ Method doesn't exist → NoSuchMethodError ❌
```

### The reverse direction (usually safer)

```
Plugin compiled against: okhttp 4.12.0
Bundle classpath has:    okhttp 5.0.0  (backward-compatible)

→ Plugin calls standard API
→ Bundle's 5.0.0 is backward-compatible
→ Works ✅ (unless 5.x introduced breaking changes)
```

### Resolution strategies

| Strategy | When to use | Complexity |
|---|---|---|
| **Version alignment** | Always — the primary defense | Low |
| **Shading with relocation** | Plugin needs a specific version, or the library is not on the bundle classpath | Medium |
| **Child-first classloader** | Not currently supported by Browser4 | N/A (future) |

#### 1. Version alignment (primary defense)

The `browser4-pdk-bom` and the root `browser4-dependencies` BOM both import
the same upstream BOMs:

```
browser4-pdk-bom:
  ├── spring-boot-dependencies:4.0.6
  ├── pulsar-bom:4.9.2
  ├── kotlin-bom:2.3.21
  └── kotlinx-coroutines-bom:1.10.2

browser4-dependencies (used by browser4-bundle):
  ├── spring-boot-dependencies:4.0.6   ← same version
  ├── pulsar-bom:4.9.2                 ← same version
  ├── kotlin-bom:2.3.21                ← same version
  └── kotlinx-coroutines-bom:1.10.2    ← same version
```

When the plugin is built against `browser4-pdk` version X and deployed to a
bundle built from the same Browser4 release, versions are guaranteed to match.

**Rule:** always use the `browser4-pdk` version that matches your target
Browser4 deployment. Check the bundle's version with:

```bash
curl http://localhost:8080/api/version
```

#### 2. Shading with relocation

When version alignment is impossible (e.g., the plugin needs a newer library
than what the bundle provides), shade the dependency and relocate its package:

```xml
<relocations>
    <relocation>
        <pattern>com.squareup.okhttp3</pattern>
        <shadedPattern>com.example.myplugin.shaded.okhttp3</shadedPattern>
    </relocation>
</relocations>
```

The relocation prevents the parent classloader from ever seeing the shaded
classes — the plugin uses `com.example.myplugin.shaded.okhttp3.OkHttpClient`
directly from its own JAR. The bundle's `com.squareup.okhttp3.OkHttpClient`
sits unused by this plugin.

**Tradeoff:** larger JAR size. A relocated OkHttp adds ~800 KB.

#### 3. Child-first classloader (not yet implemented)

A future enhancement could add an opt-in child-first classloader mode, where
the plugin JAR is searched *before* the parent. This would allow a plugin to
override bundle versions without shading. Activation would be a flag in
`browser4-plugin.json`:

```json
{
  "classLoaderMode": "childFirst"
}
```

This is **not available today**. Use shading for now.

---

## Case 4: How the Plugin Mechanism Loads Dependencies

### What happens at startup (step by step)

```
1. main() calls PluginClasspathEnhancer.enhance(Path.of("plugins"))
       │
2.     Scans plugins/ for *.jar files (sorted alphabetically)
       │
3.     Builds URLClassLoader([plugin1.jar, plugin2.jar, ...], parentClassLoader)
       │
4.     Sets it as Thread.currentThread().contextClassLoader
       │
5. runApplication<ApiApplication>(*args)
       │
6.     Spring Boot scans ALL classloaders (including plugin JARs) for
       META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
       │
7.     Plugin auto-configuration beans are created
       │
8. PluginManager.run() (ApplicationRunner)
       │
9.     Discovers all PluginMount beans in the ApplicationContext
       │
10.    Wires them into PulsarEventBus / CustomToolRegistry / BrowserResponseHandler
       │
11.    Discovers Browser4Plugin beans, calls plugin.onStartup()
```

### What the mechanism does NOT do

- ❌ **No Maven/Ivy dependency resolution at runtime** — no `pom.xml` parsing,
  no transitive dependency fetching.
- ❌ **No `lib/` directory scanning** — only top-level `.jar` files in
  `plugins/` are loaded.
- ❌ **No `MANIFEST.MF` `Class-Path` attribute processing** — the JVM's standard
  JAR `Class-Path` manifest entries are not honored by `URLClassLoader` when
  the JARs are loaded from a directory.
- ❌ **No version conflict detection** — `dependsOn` in `PluginManifest` is
  advisory; the `PluginManager` does not verify versions at load time.
- ❌ **No hot-reload** — newly installed plugins require an application restart.

### Plugin JAR: thin vs fat

```
THIN JAR (standard, recommended):
my-plugin.jar
├── META-INF/
│   ├── browser4-plugin.json
│   └── spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── com/example/myplugin/
    ├── MyPlugin.class
    ├── config/PluginAutoConfiguration.class
    ├── integration/MyBrowseEventHandler.class
    └── tools/MyToolExecutor.class
    ↑ Only the plugin's own classes. Everything else is <scope>provided</scope>.

FAT JAR (for plugins with private dependencies):
my-plugin.jar
├── META-INF/
│   ├── browser4-plugin.json
│   └── spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── com/example/myplugin/                ← plugin classes
│   ├── MyPlugin.class
│   └── ...
└── com/example/myplugin/shaded/         ← shaded third-party deps
    └── zxing/
        └── ...
```

> **Do not use `spring-boot-maven-plugin` with the `repackage` goal.** It
> produces a Spring Boot fat JAR with a `PropertiesLauncher` that is
> incompatible with `PluginClasspathEnhancer`'s `URLClassLoader`.

---

## Best Practices

### 1. Use `provided` scope for everything the bundle has

Every Browser4, Spring Boot, and Kotlin dependency must be `provided`. Only
dependencies that are *not* on the bundle classpath should be `compile` scope
(and then shaded).

### 2. Align PDK version with the target Browser4 deployment

Build plugins against the same `browser4-pdk` version as the target Browser4
instance. Check `browser4-pdk-bom/pom.xml` for the exact dependency versions
the bundle was compiled against.

### 3. Shade and relocate private dependencies

When a library is not on the bundle classpath, shade it with relocation.
Always relocate to a package under your plugin's namespace to prevent
classloader collisions.

### 4. Keep plugins thin by default

Start with a thin JAR. Only introduce shading when you have a concrete
dependency that is not on the bundle classpath. The verification script
(`bin/verify-plugin.sh`) will warn if your JAR contains embedded dependencies.

### 5. Declare dependencies in `browser4-plugin.json`

The `dependsOn` field serves as documentation and a future enforcement point:

```json
{
  "name": "browser4-images",
  "version": "4.12.0-rc.1",
  "dependsOn": ["browser4-protocol", "browser4-agentic"],
  "autoConfigurationClasses": ["ai.platon.pulsar.images.config.ImageAutoConfiguration"]
}
```

### 6. Test against the actual bundle

Before releasing a plugin, deploy it to a running Browser4 instance and check
the logs for `ClassNotFoundException` or `NoSuchMethodError`. The compile-time
classpath may differ subtly from the bundle's runtime classpath.

### 7. Verify your JAR structure

```bash
# List what's in your JAR
jar tf target/my-plugin.jar | head -30

# Check that provided deps are NOT inside
jar tf target/my-plugin.jar | grep -E "(okhttp|spring|kotlin)" && echo "WARNING: framework classes found" || echo "OK"

# Run the verification script
bin/verify-plugin.sh target/my-plugin.jar
```

---

## Quick Reference

| Scenario | Scope | Packaging | Classloader resolution |
|---|---|---|---|
| Dep on bundle classpath | `provided` | Thin JAR | Parent classloader finds it |
| Dep NOT on bundle classpath | `compile` (default) | Fat JAR with shade + relocate | Plugin classloader (via relocation) |
| Version conflict (plugin wants newer) | `compile` + shade + relocate | Fat JAR | Plugin classloader (via relocation) |
| Version conflict (bundle has newer) | `provided` | Thin JAR | Parent classloader (usually safe if backward-compatible) |

| Decision | Rule |
|---|---|
| Is it a Browser4 API? (`browser4-*`) | Always `provided` |
| Is it Spring Boot? | Always `provided` |
| Is it Kotlin stdlib/coroutines? | Always `provided` |
| Is it on `browser4-bundle`'s dependency tree? | `provided` |
| Is it truly unique to this plugin? | `compile` + shade + relocate |
| Unsure? | Check `browser4-bundle/pom.xml` and `browser4-dependencies/pom.xml` |
