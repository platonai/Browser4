# Maven Wrapper Configuration

This directory contains the Maven Wrapper configuration for the Browser4 project, ensuring reproducible builds with a pinned Maven version.

## Files

### `jvm.config`
JVM options passed to the Maven launcher. Currently enables native access for all unnamed modules:

```
--enable-native-access=ALL-UNNAMED
```

This flag is required for Java native interoperability features used by the project.

### `maven.config`
Default Maven CLI options applied to every `mvnw` invocation. Currently contains commented-out settings for parallel builds (`-T 1C`) and Kotlin incremental compilation. These are disabled because kapt (Spring/JPA annotation processing) forces non-incremental mode and some plugins (kapt, remote-resources) are not marked thread-safe.

See: https://maven.apache.org/configure.html#maven-config-file

### `wrapper/maven-wrapper.properties`
Maven Wrapper version and distribution settings:

| Property | Value |
|----------|-------|
| Wrapper version | 3.3.4 |
| Distribution type | `only-script` (scripts only, no bundled JAR) |
| Maven version | 3.9.16 |
| Distribution URL | Apache Maven Central |

## Usage

Use the wrapper scripts at the project root instead of a system-installed Maven:

- **Windows:** `mvnw.cmd <goals>`
- **Unix/macOS:** `./mvnw <goals>`

The wrapper automatically downloads the configured Maven distribution on first use.
