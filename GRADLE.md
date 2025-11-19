# Gradle Support for PulsarRPA

This project now supports both Maven and Gradle build systems.

## Quick Start with Gradle

### Prerequisites
- JDK 17 or later
- No need to install Gradle separately - the Gradle wrapper is included

### Build the Project

**Linux/macOS:**
```bash
./gradlew build
```

**Windows (Command Prompt):**
```cmd
gradlew.bat build
```

**Windows (PowerShell):**
```powershell
.\gradlew.bat build
```

### Common Gradle Commands

```bash
# Clean the build
./gradlew clean

# Compile without tests
./gradlew build -x test

# Run tests
./gradlew test

# Run tests for a specific module
./gradlew :pulsar-core:pulsar-common:test

# List all projects
./gradlew projects

# Publish to Maven Local
./gradlew publishToMavenLocal
```

## Project Structure

The Gradle build mirrors the existing Maven structure:

```
PulsarRPA/
├── build.gradle.kts          # Root build configuration
├── settings.gradle.kts        # Multi-module project settings  
├── gradle/
│   ├── wrapper/              # Gradle wrapper files
│   └── libs.versions.toml    # Centralized version management
├── gradlew                   # Gradle wrapper script (Unix)
├── gradlew.bat              # Gradle wrapper script (Windows)
└── pulsar-core/
    ├── build.gradle.kts     # Module-specific build file
    ├── pulsar-common/
    │   └── build.gradle.kts
    └── ...
```

## Configuration

### Version Catalog (libs.versions.toml)

The project uses Gradle's version catalog feature for centralized dependency management:

- **Location:** `gradle/libs.versions.toml`
- **Purpose:** Defines all version numbers, libraries, and plugins in one place
- **Benefits:** 
  - Single source of truth for versions
  - Type-safe dependency accessors
  - Easy version updates

### Root Build File (build.gradle.kts)

The root `build.gradle.kts` configures:
- Kotlin plugin version (2.2.20)
- Spring Boot plugin version (3.3.8)
- Java 17 target compatibility
- Common dependencies for all subprojects
- Publishing configuration

### Module Build Files

Each module can have its own `build.gradle.kts` to declare:
- Module-specific dependencies
- Inter-module dependencies (e.g., `implementation(project(":pulsar-core:pulsar-common"))`)
- Module-specific configurations

## Current Status

### ✅ Completed
- Gradle wrapper installation (Gradle 8.12)
- Root build configuration
- Settings for all 38 modules
- Version catalog with key dependencies
- Basic publishing configuration
- .gitignore updates for Gradle artifacts

### 🚧 Work in Progress
- Individual module build files need to be created based on their respective pom.xml files
- Dependencies from pom.xml need to be migrated to build.gradle.kts for each module

## Migration Guide

To complete the Gradle migration for a specific module:

1. **Locate the module's pom.xml**
2. **Create build.gradle.kts in the same directory**
3. **Add dependencies:**

   Maven (pom.xml):
   ```xml
   <dependency>
       <groupId>com.google.guava</groupId>
       <artifactId>guava</artifactId>
   </dependency>
   <dependency>
       <groupId>ai.platon.pulsar</groupId>
       <artifactId>pulsar-common</artifactId>
   </dependency>
   ```

   Gradle (build.gradle.kts):
   ```kotlin
   dependencies {
       implementation("com.google.guava:guava:33.3.1-jre")
       implementation(project(":pulsar-core:pulsar-common"))
   }
   ```

4. **For Spring Boot applications:**
   ```kotlin
   plugins {
       kotlin("plugin.spring")
       id("org.springframework.boot")
       id("io.spring.dependency-management")
   }
   ```

## Parallel Build Support

Both build systems can coexist:
- Maven: `./mvnw clean install`
- Gradle: `./gradlew build`

Maven remains the primary build system. Gradle support provides:
- Alternative build option for teams preferring Gradle
- Faster incremental builds
- Better IDE integration for IntelliJ IDEA and Android Studio

## Adding Dependencies

### Using Version Catalog

1. Add version to `gradle/libs.versions.toml`:
   ```toml
   [versions]
   mylib = "1.2.3"
   
   [libraries]
   mylib = { module = "com.example:mylib", version.ref = "mylib" }
   ```

2. Use in build.gradle.kts:
   ```kotlin
   dependencies {
       implementation(libs.mylib)
   }
   ```

### Direct Declaration

```kotlin
dependencies {
    implementation("com.example:library:1.2.3")
}
```

## Troubleshooting

### Build Fails with "Could not find..."
- Check that the dependency version matches what's in Maven Central
- Verify the dependency coordinates (groupId:artifactId:version)

### Module Not Found
- Ensure the module is listed in `settings.gradle.kts`
- Check the project path matches the directory structure

### Clean Build Issues
```bash
# Clean both Gradle and Maven caches
./gradlew clean
./mvnw clean
rm -rf ~/.gradle/caches/
rm -rf ~/.m2/repository/ai/platon/
```

## IDE Support

### IntelliJ IDEA
1. Open the project
2. IntelliJ will automatically detect both build systems
3. Choose "Gradle" or "Maven" in the Build Tool settings
4. Sync the project

### Eclipse
1. Install the Buildship Gradle plugin
2. Import as Gradle project
3. Or continue using m2e for Maven

## Contributing

When adding new modules or dependencies:
1. Update both `pom.xml` (Maven) and `build.gradle.kts` (Gradle)
2. Keep versions in sync between Maven and Gradle
3. Test with both build systems
4. Update this documentation if needed

## Resources

- [Gradle Documentation](https://docs.gradle.org/)
- [Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/platforms.html)
- [Maven to Gradle Migration Guide](https://docs.gradle.org/current/userguide/migrating_from_maven.html)
