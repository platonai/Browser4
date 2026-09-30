# Maven Wrapper Configuration

This directory contains the Maven Wrapper configuration for the Browser4 project, ensuring reproducible builds with a pinned Maven version.

## Files

### `jvm.config`
JVM options passed to the Maven launcher (applied to the JVM running Maven itself, not to forked test JVMs):

```
--enable-native-access=ALL-UNNAMED
-Djdk.net.URLClassPath.disableClassPathURLCheck=true
```

- `--enable-native-access=ALL-UNNAMED` — enables native access for all unnamed modules. Required for the Java native interoperability features used by the project.
- `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` — Java 25 surefire workaround: the surefire-booter JAR manifest contains absolute paths that Java 25's `URLClassPath` rejects. It must be set on the Maven JVM itself (a pom `argLine` only applies to the forked test JVM and does not reach the booter), which is why it lives in `jvm.config`.

### `maven.config`
Default Maven CLI options applied to every `mvnw` invocation (Maven 4 default options). Currently contains commented-out settings for parallel builds (`-T 1C`) and Kotlin incremental compilation. These are disabled because kapt (Spring/JPA annotation processing) forces non-incremental mode — the Kotlin daemon ignores `-Dkotlin.incremental=true` while kapt is active — and some plugins (kapt, remote-resources) are not marked thread-safe. The commented entries are kept for the day kapt is removed or incremental-kapt (`kapt.use.k2=true`) becomes stable.

See: https://maven.apache.org/configure.html#maven-config-file

### `wrapper/maven-wrapper.properties`
Maven Wrapper version and distribution settings:

| Property | Value |
|----------|-------|
| Wrapper version | 3.3.4 |
| Distribution type | `only-script` (scripts only, no bundled JAR) |
| Maven version | 3.9.16 |
| Distribution URL | Apache Maven Central |

These values pin the Maven toolchain only and are independent of the Browser4 project version. Because the distribution type is `only-script`, `wrapper/` contains just the properties file — the `mvnw` / `mvnw.cmd` launcher scripts themselves live at the project root.

## Usage

Use the wrapper scripts at the project root instead of a system-installed Maven:

- **Windows:** `mvnw.cmd <goals>`
- **Unix/macOS:** `./mvnw <goals>`

The wrapper automatically downloads the configured Maven distribution on first use.
