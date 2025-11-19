# ▶️ Build & Run Browser4

## Build from Source

### Quick Start

```shell
git clone https://github.com/platonai/Browser4.git
cd Browser4 && bin/build-run.sh
```

For Chinese developers, we strongly suggest you to follow [this](/bin/tools/maven/maven-settings.md) instruction to accelerate the building process.

## Build Systems

Browser4 supports both **Maven** (primary) and **Gradle** (alternative) build systems:

### Using Maven

Maven is the primary build system for this project.

**Windows (CMD):**
```shell
mvnw.cmd clean install -DskipTests
```

**Linux/macOS:**
```shell
./mvnw clean install -DskipTests
```

**Build with tests:**
```shell
./mvnw clean install
```

**Build a specific module:**
```shell
./mvnw -pl pulsar-core -am clean install
```

### Using Gradle

Gradle support is also available as an alternative build system.

**Linux/macOS:**
```shell
./gradlew build -x test
```

**Windows:**
```shell
gradlew.bat build -x test
```

**Build with tests:**
```shell
./gradlew build
```

**Build a specific module:**
```shell
./gradlew :pulsar-core:build
```

> 📖 For complete Gradle usage documentation, see [GRADLE.md](../../GRADLE.md)

## Common Build Tasks

### Clean Build
```shell
# Maven
./mvnw clean

# Gradle
./gradlew clean
```

### Compile Only
```shell
# Maven
./mvnw compile

# Gradle
./gradlew compileKotlin compileJava
```

### Run Tests
```shell
# Maven
./mvnw test

# Gradle
./gradlew test
```

### Install to Local Repository
```shell
# Maven
./mvnw install

# Gradle
./gradlew publishToMavenLocal
```

## Running the Application

After building, run the application:

```shell
java -jar browser4/browser4-crawler/target/Browser4.jar
```

Default port: **8182**

With custom port:
```shell
java -jar browser4/browser4-crawler/target/Browser4.jar --server.port=9090
```

## Build Tips

1. **First Build**: The first build may take longer as dependencies are downloaded
2. **Incremental Builds**: Subsequent builds are much faster
3. **Parallel Builds**: 
   - Maven: Add `-T 1C` for multi-threaded builds
   - Gradle: Enabled by default
4. **Offline Mode**:
   - Maven: Add `--offline` flag
   - Gradle: Add `--offline` flag

## Troubleshooting

### Out of Memory
Increase heap size:
```shell
# Maven
export MAVEN_OPTS="-Xmx2g"

# Gradle
export GRADLE_OPTS="-Xmx2g"
```

### Dependency Issues
Clear caches:
```shell
# Maven
rm -rf ~/.m2/repository/ai/platon/

# Gradle
./gradlew clean --refresh-dependencies
```

### Both Build Systems
You can use both build systems in parallel - they won't interfere with each other.
