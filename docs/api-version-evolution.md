# Browser4 OpenAPI Protocol and SDK Version Evolution Plan

## Table of Contents

1. [Overview](#overview)
2. [Version Numbering Scheme](#version-numbering-scheme)
3. [API Versioning Strategy](#api-versioning-strategy)
4. [SDK Versioning Strategy](#sdk-versioning-strategy)
5. [Backward Compatibility Policy](#backward-compatibility-policy)
6. [Breaking Changes Management](#breaking-changes-management)
7. [Deprecation Process](#deprecation-process)
8. [Release Management](#release-management)
9. [Migration Guidelines](#migration-guidelines)
10. [Changelog Conventions](#changelog-conventions)

---

## Overview

This document defines the version evolution strategy for Browser4's OpenAPI protocol and SDKs, ensuring predictable releases, clear backward compatibility guarantees, and smooth migration paths for users.

### Current Versions

| Component | Current Version | Status |
|-----------|----------------|--------|
| Browser4 Core | 4.5.0-SNAPSHOT | Development |
| OpenAPI Spec | 1.0.0 | Stable |
| Kotlin SDK | 4.5.0-SNAPSHOT | Development |
| Python SDK | 0.1.0 | Beta |

### Versioning Philosophy

- **Stability**: Major versions provide long-term stability with clear migration paths
- **Transparency**: All changes are documented with clear impact assessments
- **Predictability**: Users can anticipate breaking changes through semantic versioning
- **Backward Compatibility**: We maintain compatibility within major versions when possible

---

## Version Numbering Scheme

### Semantic Versioning

All Browser4 components follow [Semantic Versioning 2.0.0](https://semver.org/) (MAJOR.MINOR.PATCH):

```
MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]
```

**Examples:**
- `1.0.0` - Stable release
- `1.1.0-alpha.1` - Alpha pre-release
- `1.1.0-beta.2` - Beta pre-release
- `1.1.0-rc.1` - Release candidate
- `1.1.0+20250120` - Build metadata

### Version Component Meanings

#### MAJOR (Breaking Changes)

Increment when making incompatible API changes:
- Removing endpoints or operations
- Changing request/response schemas in incompatible ways
- Removing required backwards compatibility
- Changing authentication mechanisms
- Major architectural changes

**Impact**: Users must update code and review migration guide

#### MINOR (New Features)

Increment when adding functionality in a backward-compatible manner:
- New endpoints or operations
- New optional request parameters
- New response fields (non-breaking)
- New capabilities or features
- Performance improvements

**Impact**: Users can upgrade without code changes (but may want to use new features)

#### PATCH (Bug Fixes)

Increment when making backward-compatible bug fixes:
- Bug fixes
- Security patches
- Documentation corrections
- Minor optimizations

**Impact**: Users should upgrade immediately (no code changes required)

### Pre-release Identifiers

- **alpha**: Early development, API may change significantly
- **beta**: Feature complete, API mostly stable, testing phase
- **rc** (release candidate): Final testing before stable release

---

## API Versioning Strategy

### OpenAPI Specification Versioning

The OpenAPI specification version is **independent** from the Browser4 core version but follows semantic versioning.

#### Version Alignment Strategy

```
OpenAPI Spec v1.x.x → Browser4 Core v4.x.x
OpenAPI Spec v2.x.x → Browser4 Core v5.x.x (future)
```

#### URL-Based Versioning

Major API versions are reflected in the URL path:

```
/v1/session/{sessionId}/url          # API v1.x
/v2/session/{sessionId}/url          # API v2.x (future)
```

**Current Implementation:**
- Root endpoints (no version prefix) map to latest stable API (v1.x)
- This provides a smooth transition path for existing users

#### Header-Based Versioning (Optional)

For advanced scenarios, clients can request specific versions:

```http
Accept: application/json; version=1.0
API-Version: 1.2
```

### API Evolution Stages

Each API goes through defined lifecycle stages:

| Stage | Description | Support Level |
|-------|-------------|---------------|
| **Experimental** | Early development, may change | No guarantees |
| **Beta** | Feature complete, seeking feedback | Breaking changes possible |
| **Stable** | Production ready | Full support |
| **Deprecated** | Scheduled for removal | Maintenance only |
| **Removed** | No longer available | N/A |

**Stage Indicators in OpenAPI:**

```yaml
/session/{sessionId}/experimental/feature:
  post:
    tags:
      - experimental
    x-lifecycle: experimental
    description: |
      ⚠️ EXPERIMENTAL: This endpoint is subject to change.
```

### Version Documentation

Each OpenAPI spec version includes:
- Version number in `info.version`
- Changelog in spec description
- Deprecation notices for endpoints
- Migration notes for breaking changes

---

## SDK Versioning Strategy

### SDK Version Alignment

SDKs follow **independent versioning** but maintain compatibility with specific API versions:

```
Kotlin SDK v4.5.x → OpenAPI v1.x (Browser4 v4.5.x)
Python SDK v0.2.x → OpenAPI v1.x (Browser4 v4.5.x)
```

### SDK Version Matrix

| SDK Version | OpenAPI Version | Browser4 Core | Min Server Version |
|-------------|-----------------|---------------|--------------------|
| Kotlin 4.5.x | 1.0.x | 4.5.x | 4.5.0 |
| Python 0.2.x | 1.0.x | 4.5.x | 4.5.0 |

### SDK Release Cadence

- **Major SDK releases**: Aligned with major API version changes
- **Minor SDK releases**: New features, additional API endpoints support
- **Patch SDK releases**: Bug fixes, performance improvements

### SDK Versioning Rules

1. **Major version** (X.0.0): Breaking API changes in SDK interface
2. **Minor version** (x.Y.0): New features, new API endpoint support
3. **Patch version** (x.y.Z): Bug fixes, no API changes

### Feature Parity

SDKs strive for feature parity but may lag behind the server:

```kotlin
// SDK indicates supported API features
class Browser4Client {
    val supportedApiVersion = "1.0.0"
    val supportedFeatures = setOf("agent", "selectors", "events")
}
```

---

## Backward Compatibility Policy

### Compatibility Guarantees

Within the same **MAJOR version**, we guarantee:

✅ **Compatible Changes** (safe):
- Adding new optional parameters
- Adding new endpoints
- Adding new response fields
- Extending enum values (where semantically safe)
- Relaxing validation rules

❌ **Incompatible Changes** (breaking):
- Removing endpoints
- Removing request parameters
- Removing response fields
- Changing field types
- Making optional parameters required
- Changing error response format

### Version Support Policy

| Version Type | Support Duration | Updates |
|-------------|------------------|---------|
| **Current Major** | Until next major | All updates |
| **Previous Major** | 12 months | Critical fixes only |
| **Older Versions** | Best effort | Security patches only |

**Example Timeline:**

```
v1.0.0 released: Jan 2025
v2.0.0 released: Jan 2026
v1.x supported until: Jan 2027 (12 months after v2.0.0)
v3.0.0 released: Jan 2027
v2.x supported until: Jan 2028
v1.x support ended
```

### API Stability Levels

Different API sections may have different stability guarantees:

```yaml
paths:
  /session/{sessionId}/url:
    x-stability: stable
  
  /session/{sessionId}/agent/experimental:
    x-stability: experimental
    x-stability-notice: |
      This endpoint may change without notice.
      Not recommended for production use.
```

---

## Breaking Changes Management

### Identifying Breaking Changes

A change is considered **breaking** if:
1. Existing client code stops working
2. Existing functionality behavior changes
3. Data loss or corruption could occur
4. Security or authentication model changes

### Communicating Breaking Changes

All breaking changes must be:
1. **Documented** in the changelog with BREAKING CHANGE marker
2. **Announced** at least 3 months before release
3. **Highlighted** in release notes
4. **Explained** with migration examples

**Changelog Entry Format:**

```markdown
## [2.0.0] - 2026-01-15

### BREAKING CHANGES

- **[Session API]** Removed deprecated `capabilities.legacy` field
  - **Migration**: Use `capabilities.browserOptions` instead
  - **Impact**: Clients using `capabilities.legacy` must update
  - **Example**:
    ```diff
    - "capabilities": { "legacy": true }
    + "capabilities": { "browserOptions": { "headless": true } }
    ```
```

### Breaking Change Checklist

Before introducing a breaking change:

- [ ] Is this change absolutely necessary?
- [ ] Can it be made backward compatible?
- [ ] Is there an alternative approach?
- [ ] Have deprecation warnings been added?
- [ ] Is the migration path clear?
- [ ] Are examples updated?
- [ ] Is the changelog updated?

### Minimizing Breaking Changes

**Strategies:**
1. Use **additive changes** whenever possible
2. Introduce new fields alongside deprecated ones
3. Provide **adapter layers** for smooth transitions
4. Use **feature flags** for gradual rollout

**Example - Additive Change:**

```yaml
# Instead of removing old field
AgentRunRequest:
  properties:
    task:
      type: string
      deprecated: true
      description: Use 'instruction' instead
    instruction:
      type: string
      description: The task instruction (replaces 'task')
```

---

## Deprecation Process

### Deprecation Timeline

A complete deprecation cycle spans **at least 2 minor versions**:

1. **Version N.x**: Feature announced as deprecated
2. **Version N+1.x**: Deprecation warnings active
3. **Version N+2.0**: Feature removed

**Minimum Timeline:** 6 months from deprecation to removal

### Deprecation Announcement

**OpenAPI Spec:**

```yaml
paths:
  /session/{sessionId}/legacy/action:
    post:
      deprecated: true
      x-deprecation:
        since: "1.5.0"
        removal: "2.0.0"
        alternative: "/session/{sessionId}/agent/act"
      description: |
        ⚠️ DEPRECATED: This endpoint is deprecated since v1.5.0 
        and will be removed in v2.0.0.
        
        Please use `/session/{sessionId}/agent/act` instead.
```

**Runtime Warnings:**

```http
HTTP/1.1 200 OK
Warning: 299 - "Endpoint deprecated since v1.5.0, will be removed in v2.0.0. Use /session/{sessionId}/agent/act"
X-Deprecated-Since: 1.5.0
X-Deprecated-Removal: 2.0.0
X-Deprecated-Alternative: /session/{sessionId}/agent/act
```

### Deprecation Guidelines

1. **Never remove without deprecation** (except in alpha/beta)
2. **Always provide alternatives** with migration examples
3. **Log deprecation warnings** to help users identify usage
4. **Update documentation** to reflect deprecated status
5. **Maintain backward compatibility** during deprecation period

### SDK Deprecation

SDK methods follow the same timeline:

```kotlin
@Deprecated(
    message = "Use agentAct() instead",
    replaceWith = ReplaceWith("agentAct(action)"),
    level = DeprecationLevel.WARNING
)
fun legacyAction(action: String): Result {
    logger.warn("legacyAction() is deprecated, use agentAct()")
    return agentAct(action)
}
```

---

## Release Management

### Release Types

| Type | Description | Frequency | Example |
|------|-------------|-----------|---------|
| **Major** | Breaking changes | Yearly | 1.0.0 → 2.0.0 |
| **Minor** | New features | Monthly | 1.0.0 → 1.1.0 |
| **Patch** | Bug fixes | As needed | 1.0.0 → 1.0.1 |
| **Hotfix** | Critical security | Emergency | 1.0.1 → 1.0.2 |

### Release Workflow

#### 1. Planning Phase
- Feature proposals reviewed
- Breaking changes evaluated
- Version number determined
- Timeline established

#### 2. Development Phase
- Features implemented
- Tests written
- Documentation updated
- Changelog maintained

#### 3. Pre-release Phase
```
1.5.0-alpha.1  → Internal testing
1.5.0-beta.1   → Public testing
1.5.0-rc.1     → Release candidate
1.5.0          → Stable release
```

#### 4. Release Phase
- Tag version in Git
- Build artifacts
- Update OpenAPI spec version
- Publish SDKs to registries
- Update documentation site
- Announce release

#### 5. Post-release Phase
- Monitor for issues
- Prepare hotfixes if needed
- Gather feedback
- Plan next iteration

### Version Tag Format

```bash
# Core and API
v4.5.0              # Browser4 core release
api/v1.0.0          # OpenAPI spec release

# SDKs
sdk/kotlin/v4.5.0   # Kotlin SDK release
sdk/python/v0.2.0   # Python SDK release
```

### Release Branches

```
main                  # Latest stable
develop              # Integration branch
release/v1.1.0       # Release preparation
hotfix/v1.0.1        # Urgent fixes
```

### Release Checklist

**Pre-release:**
- [ ] All tests passing
- [ ] Documentation updated
- [ ] Changelog complete
- [ ] Migration guide ready (if breaking changes)
- [ ] Version numbers updated
- [ ] Security scan passed

**Release:**
- [ ] Git tag created
- [ ] Artifacts built and published
- [ ] Documentation site updated
- [ ] Release notes published
- [ ] Announcement sent

**Post-release:**
- [ ] Monitor error tracking
- [ ] Respond to feedback
- [ ] Update roadmap

---

## Migration Guidelines

### Migration Guide Template

For each major version, provide a comprehensive migration guide:

```markdown
# Migration Guide: v1.x → v2.0

## Overview
- **Effort Level**: Medium (2-4 hours for typical integration)
- **Breaking Changes**: 5 areas
- **Deprecated Features**: 3
- **New Features**: 10

## Breaking Changes

### 1. Authentication Changes
**What changed**: API keys now require `Bearer` prefix

**Before (v1.x):**
```http
Authorization: sk-abc123
```

**After (v2.0):**
```http
Authorization: Bearer sk-abc123
```

**Migration steps:**
1. Update authentication header format
2. Test with new format
3. Deploy changes

### 2. Response Format Changes
...
```

### Migration Tools

Provide tools to assist migration:

```bash
# CLI migration helper
browser4 migrate --from v1.5 --to v2.0 --check
browser4 migrate --from v1.5 --to v2.0 --apply

# SDK migration helper
kotlin {
    val migrator = ApiMigrator(from = "1.5.0", to = "2.0.0")
    migrator.analyzeCode("src/")
    migrator.suggestChanges()
}
```

### Version Compatibility Layer

For smooth transitions, provide compatibility shims:

```kotlin
// Compatibility layer for v1.x clients
@CompatibilityShim(targetVersion = "2.0.0")
class V1CompatibilityAdapter : RequestAdapter {
    override fun adapt(request: Request): Request {
        // Transform v1 request to v2 format
        return request.transformAuthHeader()
                     .transformCapabilities()
    }
}
```

---

## Changelog Conventions

### Changelog Format

Follow [Keep a Changelog](https://keepachangelog.com/) format:

```markdown
# Changelog

All notable changes to Browser4 OpenAPI and SDKs will be documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- New `/session/{sessionId}/agent/batch` endpoint for batch operations

### Changed
- Improved error messages for validation failures

### Deprecated
- `task` parameter in AgentRunRequest (use `instruction` instead)

### Fixed
- Fixed timeout handling in long-running agent operations

## [1.1.0] - 2025-02-15

### Added
- Event streaming via Server-Sent Events (SSE)
- Batch element operations
- Enhanced selector strategies

### Changed
- Optimized screenshot capture performance
- Updated default timeout values

### Security
- Added rate limiting to prevent abuse
- Improved API key validation

## [1.0.0] - 2025-01-15

Initial stable release.

### Added
- Complete WebDriver-compatible API
- Selector-first operations
- AI-powered agent endpoints
- PulsarSession integration
```

### Changelog Entry Categories

Use these standard categories:

- **Added**: New features
- **Changed**: Changes to existing functionality
- **Deprecated**: Soon-to-be removed features
- **Removed**: Removed features
- **Fixed**: Bug fixes
- **Security**: Security improvements
- **BREAKING**: Breaking changes (always highlighted)

### Linking Issues and PRs

```markdown
### Fixed
- Fixed session cleanup race condition ([#123](link))
- Resolved memory leak in agent operations ([#456](link))

### Added
- New batch operations endpoint ([#789](link))
  Implements RFC-001 for batch processing
```

### SDK-Specific Changelogs

Each SDK maintains its own changelog:

```
/sdks/kotlin-sdk/CHANGELOG.md
/sdks/python-sdk/CHANGELOG.md
/openapi/CHANGELOG.md
```

---

## Appendix: Version History

### OpenAPI Evolution

| Version | Release Date | Browser4 Version | Notes |
|---------|-------------|------------------|-------|
| 1.0.0 | Jan 2025 | 4.5.0 | Initial stable release |
| 1.1.0 | Feb 2025 | 4.6.0 | Event streaming added |
| 2.0.0 | Jan 2026 | 5.0.0 | Major revision (planned) |

### SDK Evolution

#### Kotlin SDK

| Version | Release Date | OpenAPI Version | Notes |
|---------|-------------|-----------------|-------|
| 4.5.0 | Jan 2025 | 1.0.0 | Initial release |
| 4.6.0 | Feb 2025 | 1.1.0 | Event streaming support |

#### Python SDK

| Version | Release Date | OpenAPI Version | Notes |
|---------|-------------|-----------------|-------|
| 0.1.0 | Jan 2025 | 1.0.0 | Beta release |
| 0.2.0 | Feb 2025 | 1.1.0 | Added event streaming |
| 1.0.0 | Mar 2025 | 1.1.0 | Stable release |

---

## References

- [Semantic Versioning 2.0.0](https://semver.org/)
- [Keep a Changelog](https://keepachangelog.com/)
- [OpenAPI Specification](https://spec.openapis.org/)
- [API Evolution Best Practices](https://opensource.zalando.com/restful-api-guidelines/)
- [W3C WebDriver Specification](https://w3c.github.io/webdriver/)

---

**Document Version**: 1.0.0  
**Last Updated**: 2025-01-20  
**Maintained by**: Browser4 Team  
**Questions**: Create an issue at https://github.com/platonai/browser4/issues
