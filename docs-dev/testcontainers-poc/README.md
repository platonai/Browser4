# Testcontainers Proof of Concept

This directory contains example code demonstrating how Testcontainers can be integrated into Browser4's testing infrastructure.

## Files

- **AbstractMongoDBTestcontainerTest.kt** — Base class for MongoDB integration tests using Testcontainers
- **WebPagePersistenceIT.kt** — Example integration test demonstrating MongoDB container usage
- **pom.xml.example** — Sample Maven configuration for Testcontainers dependencies
- **README.md** — This file

## Overview

These examples show how Testcontainers can simplify integration testing by:

1. **Automatic Container Management** — MongoDB starts automatically when tests run
2. **Zero Configuration** — No manual Docker Compose commands needed
3. **Test Isolation** — Each test class gets its own MongoDB instance
4. **Consistent Environments** — Same setup across all developer machines

## Prerequisites

To run these examples, you need:

1. **Docker** installed and running
2. **Java 17+** 
3. **Maven 3.8+**
4. **Testcontainers dependencies** (see pom.xml.example)

## Quick Start

### 1. Add Dependencies

Add to your module's `pom.xml`:

```xml
<dependencies>
    <!-- Testcontainers Core -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    
    <!-- MongoDB Container -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mongodb</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
    
    <!-- JUnit 5 Integration -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.20.4</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2. Create Your Test

Extend `AbstractMongoDBTestcontainerTest`:

```kotlin
@SpringBootTest
class MyIntegrationTest : AbstractMongoDBTestcontainerTest() {
    
    @Autowired
    lateinit var session: PulsarSession
    
    @Test
    fun testWithAutoManagedMongoDB() {
        val page = session.load("https://example.com")
        assertNotNull(page.persistentId)
    }
}
```

### 3. Run the Test

```bash
# Make sure Docker is running
docker ps

# Run your test
mvn test -Dtest=MyIntegrationTest

# Or run all integration tests with Docker requirement
mvn test -Dgroups="Integration,RequiresDocker"
```

## How It Works

1. **Test Discovery**: JUnit discovers your test class
2. **Container Startup**: Testcontainers starts MongoDB container (~5-10 seconds)
3. **Property Injection**: Spring Boot auto-configures connection details
4. **Test Execution**: Your tests run against real MongoDB
5. **Cleanup**: Container is automatically stopped and removed

## Example Output

```
[INFO] Running ai.platon.pulsar.test.WebPagePersistenceIT
[INFO] Testcontainers: Pulling docker image: mongo:7.0
[INFO] Testcontainers: Starting MongoDB container
[INFO] Testcontainers: MongoDB started on localhost:54321
[INFO] Spring Boot: Configuring MongoDB at mongodb://localhost:54321/test
[INFO] ✓ Page saved to MongoDB with ID: 123e4567-e89b-12d3-a456-426614174000
[INFO] ✓ Page retrieved from MongoDB successfully
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## Performance Considerations

### Container Startup Time

- **First run**: ~20-30 seconds (Docker image pull)
- **Subsequent runs**: ~5-10 seconds (image cached)
- **Per test class**: ~5-10 seconds (container start)

### Optimization: Container Reuse

Enable container reuse to share MongoDB across test classes:

```kotlin
companion object {
    @Container
    @JvmStatic
    val mongodb = MongoDBContainer("mongo:7.0")
        .withReuse(true) // ← Enable reuse
}
```

**Benefits:**
- Container starts once and is reused
- Reduces total test time by 80-90%
- Still provides data isolation (different databases)

**Trade-offs:**
- Requires Testcontainers Ryuk to be enabled
- Container persists between test runs (manual cleanup may be needed)

### Comparison

| Scenario | Without Reuse | With Reuse |
|----------|---------------|------------|
| 10 test classes | ~100 seconds | ~20 seconds |
| 50 test classes | ~500 seconds | ~30 seconds |

## Integration with Browser4 Tags

These tests follow Browser4's test taxonomy (see `TESTING.md`):

```kotlin
@Tag("Integration")      // Test level: Integration
@Tag("RequiresDocker")  // Environment: Docker required
@Tag("Slow")            // Cost: 5-10 seconds startup
```

**Execution:**
```bash
# Run only fast tests (excludes Testcontainers tests)
mvn test

# Run integration tests including Testcontainers
mvn test -DrunITs=true

# Run all tests
bin/test.sh all
```

## Troubleshooting

### Docker Not Running

**Error:**
```
Could not find a valid Docker environment
```

**Solution:**
```bash
# Start Docker Desktop (Mac/Windows)
# Or start Docker daemon (Linux)
sudo systemctl start docker
```

### Port Already in Use

**Error:**
```
Bind for 0.0.0.0:27017 failed: port is already allocated
```

**Solution:**
Testcontainers uses random ports by default, so this should not happen. If it does:

```bash
# Stop conflicting service
docker stop mongodb
# Or use container reuse
```

### Image Pull Fails

**Error:**
```
Failed to pull image mongo:7.0
```

**Solution:**
```bash
# Check network connection
# Or pre-pull the image
docker pull mongo:7.0
```

### Tests Hang During Startup

**Problem:** Container is starting but tests wait forever

**Solution:**
```bash
# Check Docker logs
docker logs <container-id>

# Increase timeout (rare)
mongodb.withStartupTimeout(Duration.ofMinutes(3))
```

### Container Not Stopping

**Problem:** Containers remain after tests complete

**Solution:**
```bash
# Manually stop all Testcontainers
docker ps | grep testcontainers | awk '{print $1}' | xargs docker stop

# Or use Ryuk (Testcontainers cleanup daemon)
# It's enabled by default
```

## Comparison with Current Approach

| Aspect | Current (Docker Compose) | Testcontainers |
|--------|-------------------------|----------------|
| **Setup** | Manual `docker compose up` | Automatic |
| **Configuration** | Global `docker-compose.yml` | Per-test class |
| **Isolation** | Shared instance | Independent instances |
| **CI/CD** | GitHub Actions orchestration | Built-in |
| **Cleanup** | Manual | Automatic |
| **Speed (first run)** | Fast (~2s) | Slow (~12s startup) |
| **Speed (with reuse)** | Fast (~2s) | Medium (~5s) |
| **Developer Experience** | ★★★☆☆ | ★★★★★ |

## When to Use Testcontainers

### ✅ Recommended

- **Local development testing** — Zero configuration
- **SDK contract testing** — Independent environments
- **Database migration testing** — Multiple versions
- **New feature development** — Isolated experiments

### ⚠️ Use with Caution

- **CI/CD pipelines** — Existing Docker Compose is optimized
- **Fast unit tests** — Container startup violates "Fast" tag
- **Performance-critical tests** — Startup overhead matters

### ❌ Not Recommended

- **Browser automation tests** — Browser4's Chrome Launcher is optimized
- **E2E tests** — MockSiteLauncher is more lightweight
- **Tests requiring complex service mesh** — Docker Compose is better

## Next Steps

To adopt Testcontainers in your module:

1. **Review the evaluation report**: `docs-dev/testcontainers-evaluation.md`
2. **Copy the base class**: Move `AbstractMongoDBTestcontainerTest.kt` to your test sources
3. **Add dependencies**: Update your `pom.xml` with Testcontainers
4. **Migrate tests**: Convert 2-3 integration tests as a pilot
5. **Measure results**: Compare execution time and stability
6. **Decide**: Expand, adjust, or rollback based on results

## References

- [Testcontainers Official Documentation](https://testcontainers.com/)
- [Testcontainers MongoDB Module](https://testcontainers.com/modules/mongodb/)
- [Spring Boot Testcontainers Guide](https://spring.io/guides/gs/testcontainers/)
- Browser4 Testing Guide: `../../TESTING.md`
- Testcontainers Evaluation: `../testcontainers-evaluation.md`

---

**Status**: Proof of Concept  
**Created**: 2026-02-14  
**Author**: GitHub Copilot Agent  
**Purpose**: Demonstrate Testcontainers integration approach
