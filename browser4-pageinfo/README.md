# browser4-pageinfo

Extract page title, URL and meta tags via browser-side JavaScript

## Domain: `pageinfo`

## Tools

| Method | Description |
|--------|-------------|
| `pageinfo.extractPageInfo` | Extract page title, URL and meta tags via browser-side JavaScript (runs browser-side JS via WebDriver.evaluateValue) |

## Build

```powershell
.\build.ps1                  # build + verify JAR structure
.\build.ps1 -DeployDir ..    # build + copy JAR to a plugins directory
.\build.ps1 -RestInstall     # build + install via REST API (default http://localhost:8182)
```

Or with Maven directly (from the repo root):

```bash
mvn -f browser4-pageinfo/pom.xml compile -DskipTests
mvn -f browser4-pageinfo/pom.xml package
```

## Deploy

`build.ps1 -RestInstall` installs the JAR through the REST API
(`POST /api/plugins/install`); `-DeployDir` copies it to a plugins
directory. Restart Browser4 to activate.
