# browser4-linkstats

统计页面链接分布

## Domain: `linkstats`

## Tools

| Method | Description |
|--------|-------------|
| `linkstats.summarize` | 统计页面链接分布 (runs browser-side JS via WebDriver.evaluateValue) |

## Build

```powershell
.\build.ps1                  # build + verify JAR structure
.\build.ps1 -DeployDir ..    # build + copy JAR to a plugins directory
.\build.ps1 -RestInstall     # build + install via REST API (default http://localhost:8182)
```

Or with Maven directly:

```bash
mvn -pl browser4-plugins/browser4-linkstats -am compile -DskipTests
mvn -pl browser4-plugins/browser4-linkstats package -DskipTests
```

## Deploy

`build.ps1 -RestInstall` installs the JAR through the REST API
(`POST /api/plugins/install`); `-DeployDir` copies it to a plugins
directory. Restart Browser4 to activate.