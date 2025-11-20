# OpenAPI Specification Generation

The Pulsar REST API exposes OpenAPI documentation via springdoc-openapi.

## Accessing OpenAPI Spec

When the REST API server is running, you can access:

- **JSON spec**: `http://localhost:8182/v3/api-docs`
- **YAML spec**: `http://localhost:8182/v3/api-docs.yaml`
- **Swagger UI**: `http://localhost:8182/swagger-ui.html`

## Manual Generation for SDK

To generate SDKs, you need to save the OpenAPI spec file first:

### Windows

```powershell
# 1. Start the server
mvnw.cmd -pl pulsar-rest spring-boot:run

# 2. In another terminal, fetch the spec
curl http://localhost:8182/v3/api-docs.yaml -o pulsar-rest-sdk\openapi.yaml

# 3. Stop the server (Ctrl+C)

# 4. Generate SDKs
mvnw.cmd -pl pulsar-rest-sdk generate-sources
```

### Linux/macOS

```bash
# 1. Start the server
./mvnw -pl pulsar-rest spring-boot:run

# 2. In another terminal, fetch the spec
curl http://localhost:8182/v3/api-docs.yaml -o pulsar-rest-sdk/openapi.yaml

# 3. Stop the server (Ctrl+C)

# 4. Generate SDKs
./mvnw -pl pulsar-rest-sdk generate-sources
```

## Generated SDKs

After running the SDK generation, you'll find:

- **Kotlin client**: `pulsar-rest-sdk/target/generated-sources/kotlin/`
- **Java client**: `pulsar-rest-sdk/target/generated-sources/java/`
- **TypeScript client**: `pulsar-rest-sdk/target/generated-sources/typescript/`

## Customizing OpenAPI Output

Edit `OpenApiConfig.kt` to customize API metadata like title, version, servers, etc.

Add `@Operation`, `@Parameter`, and `@Schema` annotations to controllers and DTOs for richer documentation.

