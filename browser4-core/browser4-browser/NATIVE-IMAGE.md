# Building a Native Image for MCP Browser Server

This document describes how to compile the MCP Browser Server into a standalone native Windows executable using GraalVM Native Image.

## Result (as of 2026-06-13)

| Artifact | Size | Description |
|----------|------|-------------|
| `MCPBrowserServer.jar` | 36 MB | Shaded fat JAR (maven-shade-plugin) |
| `mcp-browser-server.exe` | **9.6 MB** | Native PE32+ x86-64, compressed with UPX |
| `mcp-browser-server.exe` (no UPX) | 33 MB | Native image with `-Os` + strip, before UPX |
| `mcp-browser-server.exe` (default `-O2`) | 52 MB | Native image at default optimization level |

## Prerequisites

### 1. GraalVM JDK

Any recent GraalVM JDK with `native-image` works. Tested versions:

- **Oracle GraalVM 25.0.3+9.1** (JDK 25 LTS) — used for the 9.6 MB build
- Oracle GraalVM 24+36.1 (JDK 24)
- Oracle GraalVM 22.0.1+8.1 (JDK 22)

Set `JAVA_HOME`:

```bash
export JAVA_HOME="/d/Program Files/Java/graalvm-jdk-25.0.3+9.1"
export PATH="$JAVA_HOME/bin:$PATH"
```

Verify:

```bash
java -version
# java version "25.0.3" ... Oracle GraalVM ...

native-image.cmd --version
# native-image 25.0.3 ...
```

### 2. Microsoft Visual C++ (MSVC) Build Tools

Required for linking the native binary on Windows. Visual Studio 2022 Community was used.

```bash
# Key paths (adjust MSVC version as needed):
VS_BASE="/c/Program Files/Microsoft Visual Studio/2022/Community"
MSVC_VER=14.43.34808
SDK_VER=10.0.26100.0
```

### 3. Windows SDK

Shipped with Visual Studio. Installed versions on this machine:

- `10.0.26100.0` (used in all builds)
- `10.0.22621.0`
- `10.0.19041.0`

### 4. UPX (optional, for post-compression)

```bash
# Install via Chocolatey
choco install upx

# Or via Scoop
scoop install upx
```

## Step 1 — Build the Shaded JAR

```bash
cd /d/workspace/Browser4Team/submodules/Browser4

# Build the fat JAR with all dependencies included
mvn package -pl browser4-core/browser4-browser -am -DskipTests

# Output: browser4-core/browser4-browser/target/MCPBrowserServer.jar (~36 MB)
```

The shaded JAR is produced by `maven-shade-plugin` configured in `browser4-core/browser4-browser/pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>${version.maven-shade-plugin}</version>
    <configuration>
        <finalName>MCPBrowserServer</finalName>
        <transformers>
            <transformer implementation="...ManifestResourceTransformer">
                <mainClass>ai.platon.pulsar.browser.mcp.MCPBrowserServerRunner</mainClass>
            </transformer>
            <transformer implementation="...ServicesResourceTransformer"/>
        </transformers>
        <filters>
            <filter>
                <artifact>*:*</artifact>
                <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                </excludes>
            </filter>
        </filters>
    </configuration>
</plugin>
```

## Step 2 — Set Up MSVC Environment

`native-image` needs `cl.exe` (MSVC compiler), the Windows SDK headers, and the CRT to link the final executable.

```bash
# GraalVM
export JAVA_HOME="/d/Program Files/Java/graalvm-jdk-25.0.3+9.1"
export PATH="$JAVA_HOME/bin:$PATH"

# Visual Studio 2022 — compiler
export VS_BASE="/c/Program Files/Microsoft Visual Studio/2022/Community"
export MSVC="$VS_BASE/VC/Tools/MSVC/14.43.34808"

# Windows SDK — headers and libs
export WIN_KITS="/c/Program Files (x86)/Windows Kits/10"
export SDK_VER="10.0.26100.0"

# PATH: compiler + SDK tools
export PATH="$MSVC/bin/Hostx64/x64:$PATH"
export PATH="$WIN_KITS/bin/$SDK_VER/x64:$PATH"

# INCLUDE: headers (ucrt + shared + um + winrt)
export INCLUDE="$MSVC/include;$WIN_KITS/Include/$SDK_VER/ucrt;$WIN_KITS/Include/$SDK_VER/shared;$WIN_KITS/Include/$SDK_VER/um;$WIN_KITS/Include/$SDK_VER/winrt"

# LIB: link libraries
export LIB="$MSVC/lib/x64;$WIN_KITS/Lib/$SDK_VER/ucrt/x64;$WIN_KITS/Lib/$SDK_VER/um/x64"
```

> **Alternative:** Run `"D:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"` in a cmd.exe shell, then launch Git Bash from there. This sets all MSVC/Windows SDK environment variables automatically.

## Step 3 — Build the Native Image

### 3a. Recommended: Size-optimized build

```bash
cd browser4-core/browser4-browser/target

native-image.cmd \
    -jar MCPBrowserServer.jar \
    -o mcp-browser-server.exe \
    --no-fallback \
    -Os \
    -H:+StripDebugInfo \
    -march=compatibility \
    -J-Xmx6g
```

| Flag | Purpose |
|------|---------|
| `-jar MCPBrowserServer.jar` | Input shaded JAR |
| `-o mcp-browser-server.exe` | Output executable name |
| `--no-fallback` | Fail if native image can't be generated (don't fall back to JVM) |
| `-Os` | **Optimize for size.** Drops code area from ~12 MB to ~12 MB vs `-O2`'s ~28 MB |
| `-H:+StripDebugInfo` | Remove debug symbols from the binary |
| `-march=compatibility` | Target any x86-64 CPU (not host-specific) |
| `-J-Xmx6g` | Max heap for the native-image compiler process |

Build time: ~1m 40s on a 20-core machine.

### 3b. Default (speed-optimized) build

```bash
native-image.cmd \
    -jar MCPBrowserServer.jar \
    -o mcp-browser-server.exe \
    --no-fallback \
    -march=compatibility \
    -J-Xmx6g
```

This uses `-O2` (default). Result: ~52 MB. Build time: ~1m 20s.

### 3c. Quick build (fastest iteration, largest output)

```bash
native-image.cmd \
    -jar MCPBrowserServer.jar \
    -o mcp-browser-server.exe \
    -Ob \
    -march=compatibility \
    -J-Xmx6g
```

`-Ob` skips many optimizations. Use during development when iterating quickly.

### 3d. PGO build (best runtime performance)

```bash
# Pass 1: instrument
native-image.cmd \
    -jar MCPBrowserServer.jar \
    -o mcp-browser-server-instrumented.exe \
    --pgo-instrument \
    -march=compatibility \
    -J-Xmx6g

# Run with representative workload to collect profiling data
./mcp-browser-server-instrumented.exe &
SERVER_PID=$!
sleep 2
curl -X POST http://localhost:8182/mcp/call-tool \
    -H "Content-Type: application/json" \
    -d '{"tool":"open_session","arguments":{}}'
curl http://localhost:8182/mcp/tools
kill $SERVER_PID
# This generates default.iprof

# Pass 2: build optimized with profile
native-image.cmd \
    -jar MCPBrowserServer.jar \
    -o mcp-browser-server.exe \
    --pgo=default.iprof \
    -march=compatibility \
    -J-Xmx6g
```

## Step 4 — UPX Compression (optional)

UPX post-compresses the PE executable, typically achieving 55–70% additional reduction.

```bash
upx --best --lzma -o mcp-browser-server-upx.exe mcp-browser-server.exe
```

| UPX flag | Meaning |
|----------|---------|
| `--best` | Maximum compression (slower compression, same decompression speed) |
| `--lzma` | Use LZMA algorithm (best ratio for native images) |
| `-o <output>` | Output filename |

```bash
# Quick check: what ratio to expect
upx -t mcp-browser-server.exe
```

> **Runtime overhead:** UPX decompresses the binary into memory at launch (~50–100ms). Once decompressed, the native code runs identically. No ongoing CPU overhead for compression.

## Build Results Comparison

| Build | Flags | Code Area | Image Heap | Total File | UPX'd |
|-------|-------|-----------|------------|------------|-------|
| Default | `-O2` (default) | 27.7 MB | 23.0 MB | **51.8 MB** | ~18 MB |
| **Size** | `-Os +StripDebug` | 12.0 MB | 19.5 MB | **32.8 MB** | **9.6 MB** |
| Quick | `-Ob` | — | — | ~60 MB | ~20 MB |

## Understanding the Build Output

```
[1/8] Initializing...          — Loads the JAR, sets up the image builder
[2/8] Performing analysis...   — Static analysis: finds all reachable types/methods
[3/8] Building universe...     — Creates the closed-world "universe" of the app
[4/8] Parsing methods...       — Parses bytecode into Graal IR
[5/8] Inlining methods...      — Inlines small/hot methods
[6/8] Compiling methods...     — AOT compiles methods to x86-64 machine code
[7/8] Laying out methods...    — Places compiled code in memory layout
[8/8] Creating image...        — Writes the final PE executable
```

### Key metrics from the output

```
11,685 types, 16,681 fields, and 62,925 methods found reachable
```
Everything the app *could* use (reflection included). Static analysis prunes the rest.

```
4,129 types, 405 fields, and 2,185 methods registered for reflection
```
Types/methods accessed via reflection. Includes: Jackson serialization, Kotlin reflection, Javassist proxies, and CDP (Chrome DevTools Protocol) message deserialization.

```
5 native libraries: crypt32, ncrypt, psapi, version, winhttp
```
Windows libraries linked into the binary. Used by JDK's `java.net` HTTPS and `jdk.crypto.mscapi`.

## Troubleshooting

### `fatal error C1083: Cannot open include file: 'stdio.h'`

The `INCLUDE` environment variable is missing or wrong. Verify:

```bash
ls "$WIN_KITS/Include/$SDK_VER/ucrt/stdio.h"
```

### `Error: Native-image building is not supported on this platform`

You're running a non-GraalVM JDK. Set `JAVA_HOME` to a GraalVM installation.

### `OutOfMemoryError: Java heap space`

Increase the heap for the native-image compiler:

```bash
-J-Xmx8g  # or higher
```

### `Unrecognized option '-H:+Foo'`

Use `--expert-options` to see available flags:

```bash
native-image.cmd --expert-options
```

Or upgrade GraalVM — some `-H:` flags became `-R:` or plain `--` flags in newer versions.

### Build is slow

- Use `-Ob` for quick builds during development
- Close other applications — native-image is memory-intensive
- The `-Os` build is already faster than `-O2` because it generates less code

### Runtime error: `ClassNotFoundException` / `NoSuchMethodException`

The static analysis missed a class accessed only via reflection. Options:

1. Run the JAR with the tracing agent to generate reflection config:
   ```bash
   java -agentlib:native-image-agent=config-output-dir=./META-INF/native-image \
       -jar MCPBrowserServer.jar
   # Exercise all endpoints, then Ctrl+C
   ```
2. Rebuild with `-H:ReflectionConfigurationFiles=META-INF/native-image/reflect-config.json`

## File Locations

```
browser4-core/browser4-browser/
├── pom.xml                          # Shade plugin config + native-image profile
├── NATIVE-IMAGE.md                  # This document
├── src/main/kotlin/.../mcp/
│   ├── MCPBrowserServerRunner.kt    # main() entry point
│   ├── MCPBrowserServer.kt          # JDK HttpServer wrapper
│   ├── MCPToolDispatcher.kt         # Tool dispatch engine
│   ├── MCPSessionManager.kt         # Session lifecycle
│   └── MCPDto.kt                    # Request/Response DTOs
└── target/
    ├── MCPBrowserServer.jar         # Shaded JAR (mvn package)
    └── mcp-browser-server.exe       # Native executable
```

## Quick Reference (TL;DR)

```bash
# 1. Build shaded JAR
mvn package -pl browser4-core/browser4-browser -am -DskipTests -q

# 2. Set env
export JAVA_HOME="/d/Program Files/Java/graalvm-jdk-25.0.3+9.1"
export PATH="$JAVA_HOME/bin:/c/Program Files/Microsoft Visual Studio/2022/Community/VC/Tools/MSVC/14.43.34808/bin/Hostx64/x64:/c/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64:$PATH"
export INCLUDE="..."
export LIB="..."

# 3. Build native image
cd browser4-core/browser4-browser/target
native-image.cmd -jar MCPBrowserServer.jar -o mcp-browser-server.exe \
    --no-fallback -Os -H:+StripDebugInfo -march=compatibility -J-Xmx6g

# 4. Compress
upx --best --lzma mcp-browser-server.exe
```
