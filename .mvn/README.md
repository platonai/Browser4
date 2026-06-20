# Maven Wrapper Configuration

This directory contains the Maven Wrapper configuration for the Browser4 project, ensuring reproducible builds with a pinned Maven version.

## Files

### `jvm.config`
JVM options passed to the Maven launcher. Currently enables native access for all unnamed modules:

```
--enable-native-access=ALL-UNNAMED
```

This flag is required for Java native interoperability features used by the project.

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

## Version

Current for Browser4 4.11.x.
