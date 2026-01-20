# Version Management Quick Reference

This is a quick reference guide for managing versions in the Browser4 project. For complete details, see [Version Evolution Plan](./api-version-evolution.md).

## Current Versions

| Component | Version | File Location |
|-----------|---------|---------------|
| Browser4 Core | 4.5.0-SNAPSHOT | `pom.xml` |
| OpenAPI Spec | 1.0.0 | `openapi/openapi.yaml` (line 8) |
| Kotlin SDK | 4.5.0-SNAPSHOT | `sdks/browser4-sdk-kotlin/pom.xml` |
| Python SDK | 0.1.0 | `sdks/python-sdk/pyproject.toml` |

## When to Bump Version

### MAJOR (X.0.0) - Breaking Changes
```
✗ Removing endpoints
✗ Changing response schemas (incompatible)
✗ Removing fields
✗ Changing authentication
```
**Example**: `1.5.3 → 2.0.0`

### MINOR (x.Y.0) - New Features
```
✓ Adding new endpoints
✓ Adding optional parameters
✓ Adding response fields
✓ New capabilities
```
**Example**: `1.5.3 → 1.6.0`

### PATCH (x.y.Z) - Bug Fixes
```
✓ Bug fixes
✓ Security patches
✓ Documentation fixes
✓ Performance improvements (non-breaking)
```
**Example**: `1.5.3 → 1.5.4`

## Version Bump Checklist

### Quick Steps

1. **Update version numbers** in:
   ```bash
   # Core
   VERSION                                    # 4.5.0-SNAPSHOT
   pom.xml                                    # <version>4.5.0-SNAPSHOT</version>
   
   # OpenAPI
   openapi/openapi.yaml                       # info.version: 1.0.0
   
   # Kotlin SDK
   sdks/browser4-sdk-kotlin/pom.xml          # <version>4.5.0-SNAPSHOT</version>
   
   # Python SDK
   sdks/python-sdk/pyproject.toml            # version = "0.1.0"
   ```

2. **Update CHANGELOG**:
   ```bash
   openapi/CHANGELOG.md
   sdks/browser4-sdk-kotlin/CHANGELOG.md
   sdks/python-sdk/CHANGELOG.md
   ```

3. **Commit and tag**:
   ```bash
   git commit -am "chore: bump version to X.Y.Z"
   git tag -a vX.Y.Z -m "Release version X.Y.Z"
   git tag -a api/vX.Y.Z -m "OpenAPI specification version X.Y.Z"
   git tag -a sdk/kotlin/vX.Y.Z -m "Kotlin SDK version X.Y.Z"
   git tag -a sdk/python/vX.Y.Z -m "Python SDK version X.Y.Z"
   git push origin main --tags
   ```

## Deprecation Quick Guide

### Timeline
```
Version N.x   → Announce deprecation
Version N+1.x → Add warnings
Version N+2.0 → Remove feature
(Minimum 6 months)
```

### Mark as Deprecated in OpenAPI

```yaml
paths:
  /old/endpoint:
    post:
      deprecated: true
      x-deprecation:
        since: "1.5.0"
        removal: "2.0.0"
        alternative: "/new/endpoint"
      description: |
        ⚠️ DEPRECATED: Use /new/endpoint instead.
```

### Mark as Deprecated in Kotlin

```kotlin
@Deprecated(
    message = "Use newMethod() instead",
    replaceWith = ReplaceWith("newMethod()"),
    level = DeprecationLevel.WARNING
)
fun oldMethod() { }
```

### Mark as Deprecated in Python

```python
import warnings

def old_function():
    warnings.warn(
        "old_function is deprecated, use new_function instead",
        DeprecationWarning,
        stacklevel=2
    )
    # ... implementation
```

## Release Types

| Type | When | Example | Frequency |
|------|------|---------|-----------|
| Major | Breaking changes | 1.0.0 → 2.0.0 | Yearly |
| Minor | New features | 1.5.0 → 1.6.0 | Monthly |
| Patch | Bug fixes | 1.5.3 → 1.5.4 | As needed |
| Hotfix | Critical bugs | 1.5.3 → 1.5.4 | Emergency |

## CHANGELOG Format

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added
- New feature description

### Changed
- Changes to existing functionality

### Deprecated
- Features marked for removal
  - Deprecated since: vX.Y.Z
  - Will be removed in: vX.Y.Z
  - Alternative: use XYZ instead

### Removed
- Features removed

### Fixed
- Bug fixes

### Security
- Security improvements

### BREAKING CHANGES
- Breaking change with migration guide
```

## Common Commands

### Check Current Versions
```bash
# Core version
cat VERSION

# OpenAPI version
grep "version:" openapi/openapi.yaml | head -1

# Maven versions
./mvnw versions:display-plugin-updates

# Python SDK version
grep "^version" sdks/python-sdk/pyproject.toml
```

### Create Release
```bash
# Full release checklist
see docs/release-checklist.md

# Quick release (patch)
./mvnw clean verify
git tag -a vX.Y.Z -m "Release X.Y.Z"
git push origin main --tags
```

### Verify Compatibility
```bash
# Check for breaking changes
./mvnw -pl pulsar-rest clean test

# Verify SDK compatibility
cd sdks/browser4-sdk-kotlin && mvn test
cd sdks/python-sdk && pytest
```

## Support Policy

| Version | Support Duration | Updates |
|---------|------------------|---------|
| Current Major | Until next major | All updates |
| Previous Major | 12 months | Critical fixes |
| Older | Best effort | Security patches |

**Example Timeline**:
- v1.0.0 released: Jan 2025
- v2.0.0 released: Jan 2026
- v1.x supported until: Jan 2027 (12 months)

## Quick Links

- [Full Version Evolution Plan](./api-version-evolution.md)
- [Release Checklist](./release-checklist.md)
- [OpenAPI CHANGELOG](../openapi/CHANGELOG.md)
- [Kotlin SDK CHANGELOG](../sdks/browser4-sdk-kotlin/CHANGELOG.md)
- [Python SDK CHANGELOG](../sdks/python-sdk/CHANGELOG.md)

## Emergency Contacts

For urgent version-related issues:
- GitHub Issues: https://github.com/platonai/browser4/issues
- Release Manager: See MAINTAINERS.md

---

**Last Updated**: 2025-01-20  
**Document Version**: 1.0.0
