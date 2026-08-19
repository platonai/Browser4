# browser4-headings

Extract page headings (h1-h6) with levels

## Domain: `headings`

## Tools

| Method | Description |
|--------|-------------|
| `headings.extractHeadings` | Extract page headings (h1-h6) with levels (runs browser-side JS via WebDriver.evaluateValue) |

## Build

```powershell
.\build.ps1                  # build + verify JAR structure
.\build.ps1 -DeployDir ..    # build + copy JAR to a plugins directory
.\build.ps1 -RestInstall     # build + install via REST API (default http://localhost:8182)
```

Or with Maven directly:

```bash
mvn -pl browser4-plugins/browser4-headings -am compile -DskipTests
mvn -pl browser4-plugins/browser4-headings package -DskipTests
```

## Deploy

`build.ps1 -RestInstall` installs the JAR through the REST API
(`POST /api/plugins/install`); `-DeployDir` copies it to a plugins
directory. Restart Browser4 to activate.