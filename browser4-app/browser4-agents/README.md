# Browser4 Agents - Windows Installer

This module provides the Browser4 Agents application, which can be packaged as a Windows installer using JDK's jpackage tool.

## Prerequisites

- **JDK 17 or later** (with jpackage tool included)
- **For EXE installer**: [WiX Toolset v3.x](https://wixtoolset.org/releases/) must be installed and added to PATH

## Building the Application

### 1. Build the JAR file

```shell
mvnw clean package -DskipTests
```

This creates `target/Browser4.jar` (~314MB), a unified executable launcher.

### 2. Create Windows Application Image (Portable)

The app-image is a self-contained directory with the application and JRE, runnable without installation:

```shell
mvnw package -Pwin-jpackage -DskipTests
```

**Output**: `target/jpackage/app-image/Browser4/Browser4.exe`

This creates a portable Windows executable that can be distributed as a ZIP file. Users can run `Browser4.exe` directly without installing.

### 3. Create Windows EXE Installer

The EXE installer provides a traditional Windows installation experience:

**Requirements**: WiX Toolset v3.x must be installed and in PATH.

```shell
mvnw package -Pwin-jpackage -Djpackage.installer.skip=false -DskipTests
```

**Output**: `target/jpackage/dist/Browser4-0.0.1.exe`

This creates a Windows EXE installer that:
- Installs the application to Program Files
- Creates Start Menu shortcuts
- Provides Add/Remove Programs integration
- Includes automatic updates support

### Alternative: MSI Installer (Enterprise Deployment)

For enterprise environments that prefer MSI format:
- Better Group Policy deployment support
- Silent installation capability: `msiexec /i Browser4.msi /quiet`
- SCCM/MDT integration

To build MSI instead of EXE, modify the pom.xml `jpackage-installer-exe` execution and change `<argument>exe</argument>` to `<argument>msi</argument>`. Both formats use WiX Toolset and have similar features.

**When to use:**
- **EXE**: General users, simple installation
- **MSI**: Enterprise deployment, automation, Group Policy

## Configuration Options

The following properties can be customized:

| Property | Default | Description |
|----------|---------|-------------|
| `jpackage.skip` | `true` (false in win-jpackage profile) | Skip app-image creation |
| `jpackage.installer.skip` | `true` | Skip installer creation (requires WiX) |
| `jpackage.appVersion` | `0.0.1` | Application version (must be numeric) |

### Example: Custom Version

```shell
mvnw package -Pwin-jpackage -Djpackage.appVersion=4.5.0 -DskipTests
```

## Adding an Application Icon

To customize the application icon:

1. Create a directory: `browser4/browser4-agents/packaging/windows/`
2. Add your icon file: `Browser4.ico` (256x256 recommended)
3. Uncomment and add the icon argument in pom.xml:

```xml
<argument>--icon</argument>
<argument>${project.basedir}/packaging/windows/Browser4.ico</argument>
```

## Output Structure

After running with `-Pwin-jpackage`:

```
target/
├── Browser4.jar                              # Executable Spring Boot JAR
├── jpackage/
│   ├── input/
│   │   └── Browser4.jar                      # Staged JAR for jpackage
│   ├── app-image/
│   │   └── Browser4/
│   │       ├── Browser4.exe                  # Portable executable
│   │       ├── app/                          # Application files
│   │       └── runtime/                      # Bundled JRE
│   └── dist/                                 # Only if installer created
│       └── Browser4-0.0.1.exe                # Windows installer
```

## Running the Application

### From JAR
```shell
java -jar target/Browser4.jar
java -jar target/Browser4.jar --app=agents --server.port=9090
java -jar target/Browser4.jar --app=mcp
```

`target/Browser4.jar` is a Spring Boot executable jar, so launch it with `java -jar`.
Direct classpath invocation such as `java -cp target/Browser4.jar ai.platon.pulsar.app.Browser4Launcher`
will not work because application classes are packaged under `BOOT-INF\classes`.

### From App-Image
```shell
target\jpackage\app-image\Browser4\Browser4.exe
```

### After Installation
Use the Start Menu shortcut or run from installation directory.

## Application Configuration

The application runs on port 8182 by default. Configuration can be customized via:

1. `application.properties` in the working directory
2. Command line arguments: `Browser4.exe --app=agents --server.port=9090` or `Browser4.exe --app=mcp`
3. Environment variables

See the main project README for detailed configuration options.

## Troubleshooting

### jpackage not found
- Ensure you're using JDK 17+ (not JRE)
- Verify: `jpackage --version`

### WiX errors during installer creation
- Download and install [WiX Toolset v3.x](https://github.com/wixtoolset/wix3/releases)
- Add WiX bin directory to PATH
- Verify: `candle.exe -?` and `light.exe -?`

### Application won't start
- Check console output (window remains open for debugging)
- Verify Java 17+ is installed on target machine
- Check application logs in the working directory

## Notes

- The bundled JRE makes the package large (~314MB JAR + ~200MB JRE) but ensures consistent runtime
- Console window is enabled by default for debugging; remove `--win-console` from pom.xml for production
- App-image can be distributed as ZIP; installer provides better user experience
- The installer creates an uninstaller accessible via Windows Settings > Apps

## Related Links

- [JPackage Documentation](https://docs.oracle.com/en/java/javase/17/jpackage/)
- [WiX Toolset](https://wixtoolset.org/)
- [Spring Boot Maven Plugin](https://docs.spring.io/spring-boot/docs/current/maven-plugin/reference/htmlsingle/)
