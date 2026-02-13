# Browser4 Test Strategy

## Overview

This document defines the comprehensive testing strategy for Browser4, a multi-module Maven project with Kotlin and Java components. The strategy ensures high code quality, reliability, and maintainability while supporting rapid development cycles.

## Testing Principles

1. **Test First, Fail Fast**: Write tests for new features and bug fixes
2. **No Flaky Tests**: Zero tolerance for unreliable tests
3. **Fast Feedback**: Tests should run quickly; optimize slow tests
4. **Meaningful Coverage**: Focus on critical paths and edge cases
5. **Security First**: Test security-sensitive code paths thoroughly
6. **Isolated Tests**: Tests should be independent and repeatable

## Test Types and Distribution

### Test Mix Guidelines

The project follows this approximate distribution:

- **Unit Tests**: ~70% - Fast, isolated tests of individual components
- **Integration Tests**: ~25% - Tests of component interactions
- **E2E Tests**: ~5% - Full system tests with browser automation

### Test Type Definitions

#### Unit Tests
- **Scope**: Single class or method
- **Dependencies**: Mocked or stubbed
- **Speed**: < 100ms per test
- **Location**: `src/test/kotlin` in each module
- **Naming**: `*Test.kt`
- **Tags**: None (default)

#### Integration Tests
- **Scope**: Multiple components working together
- **Dependencies**: Real Spring beans, mock external services
- **Speed**: < 5 seconds per test
- **Location**: `pulsar-tests/` or module `src/test/kotlin`
- **Naming**: `*IT.kt` or `*Test.kt`
- **Tags**: `@Tag("IntegrationTest")`

#### E2E Tests
- **Scope**: Full system with browser automation
- **Dependencies**: Real browser, real services
- **Speed**: < 30 seconds per test
- **Location**: `pulsar-tests/`
- **Naming**: `*E2ETest.kt`
- **Tags**: `@Tag("E2ETest")`

## Coverage Targets

### Global Coverage Targets

- **Overall Coverage**: ≥ 70% instruction coverage
- **Core Logic**: ≥ 80% (pulsar-core modules)
- **Utilities**: ≥ 90% (utility classes and helpers)
- **Controllers**: ≥ 85% (REST API endpoints)

### Coverage Tools

- **JaCoCo**: For Java/Kotlin code coverage
- **Reporting**: Aggregate reports across all modules
- **CI Integration**: Enforce coverage minimums in CI pipeline

## Test Tags and Filtering

### Available Tags

- `IntegrationTest`: Multi-component integration tests
- `E2ETest`: End-to-end browser automation tests
- `ExternalServiceTest`: Tests requiring external services
- `TimeConsumingTest`: Tests taking > 10 seconds
- `HeavyTest`: Resource-intensive tests
- `SmokeTest`: Quick health check tests
- `BenchmarkTest`: Performance benchmark tests

### Tag Usage

```kotlin
@Test
@Tag("IntegrationTest")
fun `should load page with authentication`() {
    // test implementation
}

@Test
@Tag("E2ETest")
@Tag("TimeConsumingTest")
fun `should complete full user workflow`() {
    // test implementation
}
```

### Default Exclusions

By default, the following tags are excluded during normal builds:
- `TimeConsumingTest`
- `ExternalServiceTest`

These can be included explicitly when needed:
```bash
./mvnw test -DexcludedGroups=""
```

## Test Execution Strategy

### Development Workflow

1. **Quick Unit Tests**: Run frequently during development
   ```bash
   ./mvnw test -pl <module>
   ```

2. **Module Integration Tests**: Before committing
   ```bash
   ./mvnw verify -pl <module> -am
   ```

3. **Full Test Suite**: Before pushing
   ```bash
   ./bin/test.sh
   ```

### CI/CD Pipeline Stages

#### Stage 1: Fast Feedback (Per Commit)
- Unit tests only
- Duration: < 5 minutes
- Tags excluded: `IntegrationTest`, `E2ETest`, `TimeConsumingTest`

#### Stage 2: Integration (Per PR)
- Unit + Integration tests
- Duration: < 15 minutes
- Tags excluded: `E2ETest`, `TimeConsumingTest`

#### Stage 3: E2E (Pre-merge)
- Full test suite including E2E
- Duration: < 30 minutes
- All tests included

#### Stage 4: Nightly
- Full suite + performance tests
- Duration: < 60 minutes
- Includes benchmarks and stress tests

## Test Infrastructure

### Base Classes

The project provides several base classes for common test scenarios:

- `TestBase`: Core test configuration and Spring context
- `TestWebSiteAccess`: Auto-starts local mock server
- `WebDriverTestBase`: Browser automation tests

### Test Resources

- **Mock Data**: `pulsar-tests-common/src/main/resources/static/generated/`
- **Test Fixtures**: `src/test/resources/` in each module
- **Test Utilities**: `pulsar-tests-common/` module

### Test Configuration

- **Profiles**: `test`, `integration`, `e2e`
- **Spring Configuration**: Relaxed property binding
- **Environment Variables**: Support for CI-specific configuration

## Parallel Test Execution

### Configuration

The project supports parallel test execution for faster feedback:

```xml
<parallel>methods</parallel>
<threadCount>4</threadCount>
<perCoreThreadCount>true</perCoreThreadCount>
```

### Considerations

- Ensure tests are thread-safe
- Avoid shared mutable state
- Use proper test isolation
- Resource contention management

## Mocking Strategy

### Unit Tests
- Mock **all** external dependencies
- Use MockK for Kotlin, Mockito for Java
- Prefer constructor injection for testability

### Integration Tests
- Use **real** Spring beans
- Mock only true external services (APIs, databases)
- Use test profiles for configuration

### E2E Tests
- **Minimize** mocking
- Use real systems where possible
- Mock only unavoidable external dependencies

## Performance and Benchmarks

### Micro Benchmarks

Use JMH in the `pulsar-benchmarks` module:

```bash
./mvnw -pl pulsar-benchmarks -am package -DskipTests
java -jar pulsar-benchmarks/target/pulsar-benchmarks-*-shaded.jar
```

### Benchmark Guidelines

- Name: `*Benchmark.kt`
- Avoid I/O in benchmarked code
- Prepare data in `@Setup` methods
- Document expected performance and regression triggers

## Test Maintenance

### Keeping Tests Green

1. **Fix Immediately**: Don't commit failing tests
2. **No @Disabled**: Fix or remove broken tests
3. **Review Regularly**: Remove obsolete tests
4. **Update with Code**: Maintain tests alongside production code

### Flaky Test Policy

1. **Identify**: Track flaky tests immediately
2. **Investigate**: Root cause analysis required
3. **Fix or Remove**: No long-term @Disabled tests
4. **Monitor**: Track flaky test patterns

## Security Testing

### Security Test Requirements

- Test input validation and sanitization
- Test authentication and authorization
- Test for common vulnerabilities (XSS, SQL injection, etc.)
- Never commit secrets in tests
- Use test-specific credentials

### CodeQL Integration

- Run CodeQL scans on all PRs
- Address security issues before merging
- Document security test coverage

## Reporting and Metrics

### Test Reports

- **Format**: JUnit XML
- **Location**: `target/surefire-reports/`
- **Aggregation**: Combined report in CI

### Coverage Reports

- **Format**: JaCoCo HTML/XML
- **Location**: `target/site/jacoco/`
- **Threshold Enforcement**: Fail build if below targets

### CI Artifacts

- Test reports preserved for 7 days
- Failed test logs attached to build
- Coverage trends tracked over time

## Future Improvements

1. **Test Gap Analysis**: Auto-generate coverage skeleton
2. **Flaky Test Dashboard**: Track and visualize flaky tests
3. **Performance Regression**: Automated performance guards
4. **Contract Testing**: API contract validation
5. **Mutation Testing**: Improve test quality metrics

## References

- [TESTING.md](TESTING.md) - Quick start guide for running tests
- [test-guide.md](../devdocs/copilot/test-guide.md) - Detailed testing guide
- [mvn-test.md](../devdocs/development/mvn-test.md) - Maven test process control
