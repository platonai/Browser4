# Browser4 Release Checklist

This document provides a comprehensive checklist for Browser4 releases, covering OpenAPI specification, core components, and SDKs.

## Pre-Release Phase

### Version Planning

- [ ] **Determine version number** based on changes
  - [ ] Major version (X.0.0) - Breaking changes
  - [ ] Minor version (x.Y.0) - New features
  - [ ] Patch version (x.y.Z) - Bug fixes
- [ ] **Review breaking changes** and ensure they are necessary
- [ ] **Document deprecations** with clear migration paths
- [ ] **Set release date** and communicate to stakeholders

### Code Preparation

- [ ] **All features implemented** and merged to develop branch
- [ ] **All tests passing** on CI/CD pipeline
  - [ ] Unit tests
  - [ ] Integration tests
  - [ ] E2E tests
- [ ] **Code review completed** for all changes
- [ ] **Security scan passed** (CodeQL, dependency scan)
- [ ] **Performance benchmarks run** (if applicable)
- [ ] **No critical bugs** in issue tracker

### Documentation Updates

- [ ] **Update OpenAPI specification** (`openapi/openapi.yaml`)
  - [ ] Version number in `info.version`
  - [ ] New endpoints documented
  - [ ] Schema changes reflected
  - [ ] Deprecation notices added
- [ ] **Update API documentation** (`openapi/openapi.md`)
- [ ] **Update README files**
  - [ ] Root README.md
  - [ ] SDK READMEs
- [ ] **Update version files**
  - [ ] `VERSION` file
  - [ ] `pom.xml` files
  - [ ] `pyproject.toml` for Python SDK
- [ ] **Update CHANGELOG files**
  - [ ] `openapi/CHANGELOG.md`
  - [ ] `sdks/browser4-sdk-kotlin/CHANGELOG.md`
  - [ ] `sdks/python-sdk/CHANGELOG.md`
- [ ] **Create/update migration guide** (for major/minor releases)
- [ ] **Update examples** to use latest API
- [ ] **Review and update docstrings/KDoc**

### Changelog Preparation

For each changelog, ensure:

- [ ] **All changes categorized**
  - Added
  - Changed
  - Deprecated
  - Removed
  - Fixed
  - Security
  - BREAKING (if applicable)
- [ ] **Release date set**
- [ ] **Version number correct**
- [ ] **Links to issues/PRs included**
- [ ] **Migration notes for breaking changes**

### Migration Guide (if needed)

- [ ] **Overview section** with effort level estimate
- [ ] **Breaking changes documented** with before/after examples
- [ ] **Step-by-step migration instructions**
- [ ] **Code examples** for common scenarios
- [ ] **Deprecation timeline** clearly stated
- [ ] **FAQ section** for common migration issues

## Pre-Release Testing

### Alpha/Beta Release

- [ ] **Create release branch** (`release/vX.Y.Z`)
- [ ] **Tag alpha version** (`vX.Y.Z-alpha.1`)
- [ ] **Build and test artifacts**
- [ ] **Internal testing completed**
- [ ] **Tag beta version** (`vX.Y.Z-beta.1`)
- [ ] **Announce beta to early adopters**
- [ ] **Collect and address feedback**
- [ ] **Tag release candidate** (`vX.Y.Z-rc.1`)

### Release Candidate Testing

- [ ] **Full regression testing**
- [ ] **Cross-platform testing**
  - [ ] Windows
  - [ ] macOS
  - [ ] Linux
- [ ] **Browser compatibility testing**
- [ ] **SDK integration testing**
  - [ ] Kotlin SDK with server
  - [ ] Python SDK with server
- [ ] **Load testing** (if significant performance changes)
- [ ] **Security review** completed
- [ ] **Documentation review** completed

## Release Phase

### Version Control

- [ ] **Create release branch** (if not already created)
  ```bash
  git checkout -b release/vX.Y.Z develop
  ```
- [ ] **Update version numbers** in all files
- [ ] **Commit version changes**
  ```bash
  git commit -am "chore: bump version to X.Y.Z"
  ```
- [ ] **Merge to main**
  ```bash
  git checkout main
  git merge release/vX.Y.Z
  ```
- [ ] **Tag release**
  ```bash
  git tag -a vX.Y.Z -m "Release version X.Y.Z"
  git tag -a api/vX.Y.Z -m "OpenAPI specification version X.Y.Z"
  git tag -a sdk/kotlin/vX.Y.Z -m "Kotlin SDK version X.Y.Z"
  git tag -a sdk/python/vX.Y.Z -m "Python SDK version X.Y.Z"
  ```
- [ ] **Push tags**
  ```bash
  git push origin main
  git push origin --tags
  ```
- [ ] **Merge back to develop**
  ```bash
  git checkout develop
  git merge main
  git push origin develop
  ```

### Artifact Building

#### Core & REST API

- [ ] **Build Browser4 core**
  ```bash
  ./mvnw clean package -DskipTests
  ```
- [ ] **Build with tests**
  ```bash
  ./mvnw clean verify
  ```
- [ ] **Build distribution artifacts**
- [ ] **Sign artifacts** (if required)
- [ ] **Verify artifact integrity**

#### Kotlin SDK

- [ ] **Build Kotlin SDK**
  ```bash
  ./mvnw -pl sdks/browser4-sdk-kotlin clean package
  ```
- [ ] **Run SDK tests**
  ```bash
  ./mvnw -pl sdks/browser4-sdk-kotlin test
  ```
- [ ] **Generate KDoc documentation**
- [ ] **Publish to Maven Central** (if stable release)
  ```bash
  ./mvnw -pl sdks/browser4-sdk-kotlin deploy
  ```

#### Python SDK

- [ ] **Update version in pyproject.toml**
- [ ] **Build Python package**
  ```bash
  cd sdks/python-sdk
  python -m build
  ```
- [ ] **Run tests**
  ```bash
  pytest tests/
  ```
- [ ] **Check package**
  ```bash
  twine check dist/*
  ```
- [ ] **Publish to PyPI** (if stable release)
  ```bash
  twine upload dist/*
  ```

### Docker Images

- [ ] **Build Docker image**
  ```bash
  docker build -t galaxyeye88/browser4:X.Y.Z .
  docker build -t galaxyeye88/browser4:latest .
  ```
- [ ] **Test Docker image**
- [ ] **Push to Docker Hub**
  ```bash
  docker push galaxyeye88/browser4:X.Y.Z
  docker push galaxyeye88/browser4:latest
  ```

### GitHub Release

- [ ] **Create GitHub release** from tag
- [ ] **Upload artifacts**
  - JAR files
  - Distribution archives
  - Checksums
- [ ] **Write release notes**
  - Highlights
  - Breaking changes (if any)
  - New features
  - Bug fixes
  - Migration guide link (if applicable)
- [ ] **Set as latest release** (if stable)
- [ ] **Mark as pre-release** (if alpha/beta/rc)

## Post-Release Phase

### Announcement

- [ ] **Update website** (if applicable)
- [ ] **Publish blog post** announcing release
- [ ] **Social media announcements**
  - Twitter/X
  - LinkedIn
  - Reddit (r/programming, relevant subreddits)
- [ ] **Notify mailing list/newsletter**
- [ ] **Update documentation site**
- [ ] **Announce in community channels**
  - Discord/Slack
  - GitHub Discussions

### Package Registries

- [ ] **Verify Maven Central** publication (Kotlin SDK)
- [ ] **Verify PyPI** publication (Python SDK)
- [ ] **Verify Docker Hub** publication
- [ ] **Update package registry badges** in README

### Monitoring

- [ ] **Monitor error tracking** for new issues
- [ ] **Monitor GitHub issues** for release-related problems
- [ ] **Monitor CI/CD** for downstream failures
- [ ] **Monitor download statistics**
- [ ] **Monitor Docker Hub pulls**
- [ ] **Check SDK installation** works correctly

### Issue Tracker Cleanup

- [ ] **Close resolved issues** included in release
- [ ] **Update milestones**
- [ ] **Create next milestone**
- [ ] **Triage remaining issues**
- [ ] **Update project board**

### Documentation Site

- [ ] **Update version selector** (if applicable)
- [ ] **Add release notes page**
- [ ] **Update API reference**
- [ ] **Update getting started guides**
- [ ] **Archive old version docs** (if removing support)

## Hotfix Release Checklist

For urgent security or critical bug fixes:

- [ ] **Create hotfix branch** from main
  ```bash
  git checkout -b hotfix/vX.Y.Z main
  ```
- [ ] **Apply minimal fix**
- [ ] **Update PATCH version** only
- [ ] **Update CHANGELOG** with fix details
- [ ] **Run critical tests**
- [ ] **Merge to main and develop**
- [ ] **Tag and release** following release phase steps
- [ ] **Fast-track testing** (abbreviated test cycle)
- [ ] **Urgent announcements** emphasizing security/criticality

## Rollback Procedure

If critical issues are discovered post-release:

- [ ] **Assess severity** - Can it be hotfixed or needs rollback?
- [ ] **Communicate immediately** to users
- [ ] **Remove "latest" tag** from problematic release
- [ ] **Mark release as "yanked"** on package registries (if supported)
- [ ] **Revert Docker image tags**
- [ ] **Document known issues** prominently
- [ ] **Prepare hotfix** or rollback version
- [ ] **Post-mortem analysis** after resolution

## Version-Specific Checklists

### Major Version (X.0.0)

Additional items for major releases:

- [ ] **Major version migration guide** complete
- [ ] **Deprecation timeline** clearly communicated
- [ ] **Backwards compatibility layer** considered
- [ ] **Extended testing period** (minimum 4 weeks)
- [ ] **User communication** at least 3 months in advance
- [ ] **Legacy version support plan** documented

### Minor Version (x.Y.0)

Additional items for minor releases:

- [ ] **New feature documentation** complete
- [ ] **Examples for new features** added
- [ ] **Backwards compatibility** verified
- [ ] **Performance impact** assessed

### Patch Version (x.y.Z)

Focus on:

- [ ] **Bug fix verification**
- [ ] **No new features** included
- [ ] **No API changes**
- [ ] **Fast release cycle** (can be same day if critical)

## Tools & Automation

### Scripts to Run

```bash
# Version bump script
./scripts/bump-version.sh X.Y.Z

# Changelog update
./scripts/update-changelog.sh

# Build all artifacts
./scripts/build-all.sh

# Run full test suite
./scripts/test-all.sh

# Create release artifacts
./scripts/create-release.sh X.Y.Z
```

### CI/CD Pipeline

- [ ] **Release pipeline triggered**
- [ ] **All stages passed**
  - Build
  - Test
  - Security scan
  - Artifact creation
  - Deployment
- [ ] **Deployment to staging** verified
- [ ] **Deployment to production** completed

## Sign-off

### Release Manager

- [ ] **All checklist items completed**
- [ ] **Artifacts verified**
- [ ] **Documentation updated**
- [ ] **Announcements sent**

**Release Manager**: ___________________  
**Date**: ___________________  
**Version**: ___________________  

### Quality Assurance

- [ ] **Testing completed**
- [ ] **No critical bugs**
- [ ] **Documentation reviewed**

**QA Lead**: ___________________  
**Date**: ___________________  

---

## References

- [Version Evolution Plan](../docs/api-version-evolution.md)
- [Semantic Versioning](https://semver.org/)
- [Keep a Changelog](https://keepachangelog.com/)
- [GitHub Release Guide](https://docs.github.com/en/repositories/releasing-projects-on-github)

---

**Template Version**: 1.0.0  
**Last Updated**: 2025-01-20
