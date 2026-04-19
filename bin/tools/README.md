# Browser4 Dependency Management Tools

This directory contains tools and scripts for managing project dependencies.

## Available Tools

### 1. Dependency Health Check

**Scripts:**
- `check-dependencies.sh` (Linux/macOS)
- `check-dependencies.ps1` (Windows)

**Purpose:** Comprehensive dependency health check including:
- Available dependency updates
- Available plugin updates
- Dependency usage analysis
- Version conflict detection
- Security vulnerability scanning (with `--full` flag)

**Usage:**

Linux/macOS:
```bash
# Basic check
./bin/tools/check-dependencies.sh

# Full check including security scan
./bin/tools/check-dependencies.sh --full
```

Windows:
```powershell
# Basic check
.\bin\tools\check-dependencies.ps1

# Full check including security scan
.\bin\tools\check-dependencies.ps1 -Full
```

**Output:**
Reports are generated in `target/dependency-reports/`:
- `dependency-updates.txt`: Available dependency updates
- `plugin-updates.txt`: Available plugin updates
- `property-updates.txt`: Available property updates
- `dependency-analysis.txt`: Dependency usage analysis
- `dependency-tree.txt`: Full dependency tree
- `security-check.log`: Security scan log (with --full)
- `summary.txt`: Summary report

### 2. Maven Version Rules

**File:** `maven-version-rules.xml` (in project root)

**Purpose:** Configure which versions the Maven Versions Plugin should consider valid updates.

**Features:**
- Ignores pre-release versions (alpha, beta, RC, snapshots)
- Controls major version upgrades for critical dependencies
- Customizable per dependency

**Modify to:**
- Allow specific pre-release versions
- Change version constraints for dependencies
- Add new dependency-specific rules

### 3. Upgrade Checklist

**File:** `docs/dependency-upgrade-checklist.md`

**Purpose:** Comprehensive checklist template for dependency upgrades.

**Use when:**
- Planning a dependency upgrade
- Executing an upgrade
- Reviewing an upgrade PR

**Sections:**
- Pre-upgrade research and planning
- Implementation steps
- Testing requirements
- Validation checks
- Post-merge monitoring
- Rollback procedures

## Quick Reference Commands

### Check for Updates

```bash
# Display available dependency updates
./mvnw versions:display-dependency-updates

# Display available plugin updates
./mvnw versions:display-plugin-updates

# Display available property updates
./mvnw versions:display-property-updates
```

### Update Dependencies

```bash
# Update specific dependency to latest
./mvnw versions:use-latest-versions \
  -Dincludes=groupId:artifactId

# Update specific dependency to specific version
./mvnw versions:use-dep-version \
  -Dincludes=groupId:artifactId \
  -DdepVersion=x.y.z

# Update all properties to latest (CAREFUL!)
./mvnw versions:update-properties
```

### Analyze Dependencies

```bash
# Show full dependency tree
./mvnw dependency:tree

# Analyze for unused/undeclared dependencies
./mvnw dependency:analyze

# List all dependencies
./mvnw dependency:list

# Check for dependency conflicts
./mvnw dependency:tree -Dverbose
```

### Security Scanning

```bash
# Run OWASP dependency check
./mvnw dependency-check:check

# Generate aggregate report for all modules
./mvnw dependency-check:aggregate

# Update CVE database
./mvnw dependency-check:update-only

# Purge local CVE database
./mvnw dependency-check:purge
```

### Useful Combinations

```bash
# Find specific dependency in tree
./mvnw dependency:tree | grep -i "dependency-name"

# Check for transitive dependencies of specific artifact
./mvnw dependency:tree -Dincludes=groupId:artifactId

# Exclude certain scopes from tree
./mvnw dependency:tree -Dscope=compile

# Export dependency tree to file
./mvnw dependency:tree > dependency-tree.txt
```

## Workflows

### Weekly Maintenance

```bash
# 1. Check for updates
./bin/tools/check-dependencies.sh

# 2. Review reports in target/dependency-reports/

# 3. Prioritize updates based on:
#    - Security vulnerabilities (P0)
#    - Critical bugs (P0-P1)
#    - Performance improvements (P1-P2)
#    - Regular updates (P2-P3)

# 4. Plan upgrades according to docs/dependency-upgrade-plan.md
```

### Monthly Security Scan

```bash
# Run full security check
./bin/tools/check-dependencies.sh --full

# Review: target/dependency-check-report.html

# Address high/critical issues immediately
```

### Before Major Release

```bash
# 1. Full dependency health check
./bin/tools/check-dependencies.sh --full

# 2. Update all non-breaking dependencies
./mvnw versions:display-dependency-updates

# 3. Run complete test suite
./mvnw clean verify

# 4. Performance benchmarks
./mvnw -pl pulsar-benchmarks verify

# 5. Document all changes
```

## Integration with CI/CD

The dependency check can be integrated into CI/CD pipelines:

### GitHub Actions Example

```yaml
- name: Dependency Health Check
  run: |
    chmod +x bin/tools/check-dependencies.sh
    ./bin/tools/check-dependencies.sh --full

- name: Upload Reports
  uses: actions/upload-artifact@v4
  with:
    name: dependency-reports
    path: target/dependency-reports/
```

### Jenkins Example

```groovy
stage('Dependency Check') {
    steps {
        sh 'bin/tools/check-dependencies.sh --full'
        publishHTML([
            reportDir: 'target',
            reportFiles: 'dependency-check-report.html',
            reportName: 'Dependency Security Report'
        ])
    }
}
```

## Troubleshooting

### Maven Versions Plugin Not Working

**Issue:** Commands fail with plugin not found
**Solution:**
```bash
# Add plugin to pulsar-parent/pom.xml
# Or use full coordinates:
./mvnw org.codehaus.mojo:versions-maven-plugin:2.18.0:display-dependency-updates
```

### OWASP Check Takes Too Long

**Issue:** First run downloads CVE database (can take 10-20 minutes)
**Solution:**
- Be patient on first run
- Subsequent runs are much faster (~1-2 minutes)
- Run `./mvnw dependency-check:update-only` separately to pre-download

### False Positives in Security Scan

**Issue:** OWASP reports false positives
**Solution:** Create suppressions file `owasp-suppressions.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress>
    <notes>False positive explanation</notes>
    <cve>CVE-2023-XXXXX</cve>
  </suppress>
</suppressions>
```

Then reference in plugin config:
```xml
<configuration>
  <suppressionFile>owasp-suppressions.xml</suppressionFile>
</configuration>
```

### Dependency Conflicts

**Issue:** Multiple versions of same dependency in tree
**Solution:**
```bash
# 1. Identify conflict
./mvnw dependency:tree -Dverbose | grep "omitted for conflict"

# 2. Force version in browser4-dependencies/pom.xml:
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>conflict-group</groupId>
      <artifactId>conflict-artifact</artifactId>
      <version>desired-version</version>
    </dependency>
  </dependencies>
</dependencyManagement>

# 3. Or exclude unwanted version:
<dependency>
  <groupId>some-dependency</groupId>
  <artifactId>some-artifact</artifactId>
  <exclusions>
    <exclusion>
      <groupId>conflict-group</groupId>
      <artifactId>conflict-artifact</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

## Best Practices

1. **Run checks weekly**: Keep up with security updates
2. **Review before upgrading**: Always read release notes
3. **Test thoroughly**: Never skip testing after upgrades
4. **One at a time**: Upgrade one dependency at a time
5. **Document changes**: Use the upgrade checklist
6. **Monitor after merge**: Watch for issues post-deployment

## Additional Resources

- [Dependency Upgrade Plan](../docs/dependency-upgrade-plan.md)
- [Dependency Upgrade Checklist](../docs/dependency-upgrade-checklist.md)
- [Maven Versions Plugin Docs](https://www.mojohaus.org/versions-maven-plugin/)
- [OWASP Dependency Check](https://owasp.org/www-project-dependency-check/)

## Contributing

To add new tools or improve existing ones:

1. Create the tool in `bin/tools/`
2. Add documentation here
3. Update the main upgrade plan document
4. Submit a PR with examples

---

**Maintained by:** Browser4 Team
**Last Updated:** 2026-01-14
**Questions?** Open an issue or contact the maintainers
