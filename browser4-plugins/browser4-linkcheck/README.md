# browser4-linkcheck

A Browser4 plugin that counts the links on the current page and reports the
total number of links, split into external and internal links.

## Functionality

The plugin registers the `linkcheck` domain with the tool
`linkcheck.countLinks()`. The tool runs a browser-side JavaScript script
(`linkcheck/countLinks.js`) in the real page context via
`WebDriver.evaluateValue`, so it sees the fully rendered DOM, and returns a
`LinkCountResult` containing:

- `total`: the number of all `a[href]` elements on the page
- `external`: absolute `http`/`https` links whose origin differs from the page origin
- `internal`: the remaining links — relative links, `#` anchors, `mailto:`,
  `tel:`, and same-origin absolute links

## Build

```bash
mvn -f pom.xml package
```

This compiles the Kotlin sources, runs the tests, and produces the plugin JAR
under `target/browser4-linkcheck-<version>.jar`.

## Verify the JAR structure

After the build, confirm that the JAR contains the required plugin entries:

```bash
jar tf target/browser4-linkcheck-*.jar
```

The output must include:

| Entry | Purpose |
|-------|---------|
| `META-INF/browser4-plugin.json` | Plugin manifest (name, version, SDK version, dependencies) |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot auto-configuration registration |
| `linkcheck/countLinks.js` | Browser-side script that counts total/external/internal links |
| `ai/platon/pulsar/linkcheck/config/LinkcheckAutoConfiguration.class` | Auto-configuration class |
| `ai/platon/pulsar/linkcheck/tools/LinkcheckToolExecutor.class` | Tool executor exposing `linkcheck.countLinks` |

## Deploy

Use the provided build script:

```powershell
.\build.ps1                # build + verify JAR structure
.\build.ps1 -DeployDir ..  # build + copy the JAR to a plugins directory
.\build.ps1 -RestInstall   # build + install via the REST API (default http://localhost:8182)
```

Alternatively, copy the built JAR into the Browser4 `plugins` directory and
restart Browser4 to pick it up.
