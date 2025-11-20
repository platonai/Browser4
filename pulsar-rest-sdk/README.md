# Pulsar REST SDKs

This module generates client SDKs from the Pulsar REST OpenAPI specification.

## Prerequisites

1. Place the OpenAPI spec file at `pulsar-rest-sdk/openapi.yaml`
2. See `pulsar-rest/README-OPENAPI.md` for instructions on fetching the spec from the running server

## Generated Outputs

After running `mvn generate-sources`, you'll find:

- **Kotlin client**: `target/generated-sources/kotlin/`
- **Java client**: `target/generated-sources/java/`
- **TypeScript client**: `target/generated-sources/typescript/`

## Quick Start

### Windows

```bat
REM Fetch spec from running server
curl http://localhost:8182/v3/api-docs.yaml -o openapi.yaml

REM Generate SDKs
mvnw.cmd generate-sources
```

### Linux/macOS

```bash
# Fetch spec from running server
curl http://localhost:8182/v3/api-docs.yaml -o openapi.yaml

# Generate SDKs
./mvnw generate-sources
```

## Usage Examples

### Kotlin SDK
See complete examples in [`examples/kotlin/`](examples/kotlin/):

- **QuickStart.kt** - Minimal example to get started quickly
- **CommandApiExample.kt** - Comprehensive examples covering all API features
- **README.md** - Detailed usage guide and API reference

**Quick example**:
```kotlin
import ai.platon.pulsar.sdk.kotlin.ApiClient
import ai.platon.pulsar.sdk.kotlin.apis.CommandControllerApi
import ai.platon.pulsar.sdk.kotlin.models.CommandRequest

val apiClient = ApiClient(baseUrl = "http://localhost:8182")
val api = apiClient.createWebservice(CommandControllerApi::class.java)

val request = CommandRequest(
    url = "https://example.com",
    pageSummaryPrompt = "Summarize this page",
    mode = "sync"
)

val status = api.submitCommand(request)
println(status.commandResult?.pageSummary)
```

### Java SDK
Generated Java client uses RestTemplate and is compatible with Jakarta EE.

### TypeScript SDK
Generated TypeScript client uses Axios with full TypeScript typings.

## Publishing

- **TypeScript/npm**: Navigate to `target/generated-sources/typescript/` and run `npm publish`
- **Maven**: Generated Java/Kotlin clients can be packaged and deployed as Maven artifacts


