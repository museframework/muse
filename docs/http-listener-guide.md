# HTTP Listener Source - User Guide

## Overview

The HTTP Listener Source allows Muse flows to receive HTTP requests and respond with HTTP responses. It enables building REST APIs, webhooks, and HTTP-based integrations.

**Key Features:**
- Framework-neutral core with Spring Boot and Quarkus adapter modules
- Configurable lifecycle (EAGER/LAZY initialization)
- Typed methods `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, and `TRACE`, plus listener wildcard `ANY`
- Path matching with variables
- Query parameter and header extraction
- Custom response configuration
- Correlation ID propagation

## Streaming Payloads

HTTP listener requests expose request bodies as repeatable streaming providers. Inside the flow, the payload value is a `MuseCursorStreamProvider` when the adapter receives a streaming body. Consumers should open a fresh cursor for each read:

```kotlin
val provider = event.payload?.asCursorStreamProvider()
val bytes = provider?.openCursor()?.use { it.readBytes() }
```

Spring HTTP listeners use repeatable file-store streaming by default. Configure the in-memory threshold and temp directory with:

```properties
muse.streaming.file-store.in-memory-size-bytes=524288
muse.streaming.file-store.temp-directory=/tmp/muse-streaming
muse.streaming.file-store.delete-files-on-close=true
```

Responses preserve `MuseCursorStreamProvider` payloads by default. Set `responseStreamingMode` to `ALWAYS` to force chunked transfer or `NEVER` to materialize a provider before writing the response.

## Quick Start

### Minimal Example

```kotlin
@MuseConfig
@Component
class MyHttpConfig : MuseConfigDefinition() {
    private val listenerConnection =
        httpListenerConnection("hello-listener") {
            port = 8080
        }

    val helloFlow = flow("hello-flow") {
        docName = "Hello World HTTP API"

        httpListener(listenerConnection) {
            docName = "Hello Listener"
            path = "/api/hello"
            allowedMethods = listOf(HttpMethod.GET)
        }

        setPayload {
            value = "\"hello world\""
        }
    }
}
```

### Application Setup

```kotlin
@MuseApplication(webEnvironment = WebApplicationType.SERVLET)
class MyApplication

fun main(args: Array<String>) {
    runMuseApplication<MyApplication>(args)
}
```

**Important:** Use `WebApplicationType.SERVLET` to enable embedded Tomcat.

## Configuration

### HTTP Listener DSL

```kotlin
val listenerConnection =
    httpListenerConnection("public-http") {
        host = "0.0.0.0"
        port = 8080
        protocol = HttpProtocol.HTTP
        basePath = "/api"
        usePersistentConnections = true
        connectionIdleTimeoutMs = 40_000
        readTimeoutMs = 30_000
    }

httpListener(listenerConnection) {
    path = "/users/{id}"
    docName = ""
    allowedMethods = listOf(HttpMethod.ANY)
    timeoutMs = null
    lifecycle = HttpListenerLifecycle.EAGER
    responseStreamingMode = HttpStreamingMode.AUTO

    successResponse {
        statusCode = "#[vars.httpStatus default 200]"
        reasonPhrase = "#[vars.httpReason]"
        headersExpression = "#[vars.responseHeaders]"
        header("Content-Type", "application/json")
        body = "#[payload]"
    }

    errorResponse {
        statusCode = "#[vars.httpStatus default 500]"
        reasonPhrase = "#[error.description]"
        headersExpression = "#[vars.errorHeaders]"
        body = "#[error.description]"
    }
}
```

### Connection Configuration

```kotlin
val listenerConnection =
    httpListenerConnection("public-http") {
        host = "0.0.0.0"              // Bind address
        port = 8080                    // HTTP port
        protocol = HttpProtocol.HTTP
        basePath = "/api"              // Prefix joined with every listener path
        usePersistentConnections = true
        connectionIdleTimeoutMs = 40_000
        readTimeoutMs = 30_000
    }
```

The Spring adapter attaches handlers to the application's existing servlet server rather than creating a connector-owned socket. Configure actual bind addresses, keep-alive, and server I/O timeouts through Spring Boot; the Muse connection values remain the framework-neutral contract used by other adapters and Babelfish translation.

### Request Attributes

Listener events expose `HttpAttributes` with:

- decoded and raw paths: `requestPath`, `rawRequestPath`;
- decoded and raw URIs: `requestUri`, `rawRequestUri`, plus `queryString`;
- route metadata: `listenerPath`, `relativePath`, `maskedRequestPath`, `uriParams`;
- transport metadata: `method`, `version`, `scheme`, `localAddress`, `remoteAddress`;
- `headers`, `queryParams`, and an optional `clientCertificate`.

Muse currently models headers and query parameters as single-value maps; Mule's attributes retain repeated values. The Spring adapter selects the first request value.

`reasonPhrase` is retained in Muse response models and requester attributes. Servlet APIs no longer support custom wire reason phrases, so the Spring listener sends the configured status code but cannot override its protocol reason text.

Muse does not currently implement Mule's `deferredResponse` flag. The flag is a response-writer scheduling policy, not an early `202 Accepted` acknowledgement: the client still waits for the configured status, headers, and body. Although flow execution crosses Muse's coroutine event-envelope boundary, the Spring adapter currently serializes the body inline on the Servlet worker. A slow or large body can therefore retain that worker.

The proposal preserves the listener SPI decisions in [ADR-017](adr/ADR-017-http-listener-spi.md) and the streaming ownership rules in [ADR-018](adr/ADR-018-repeatable-streaming.md). These links describe proposed work, not behavior available in the current runtime.

## Lifecycle Modes

### EAGER (Default)

Server starts immediately when the flow initializes:

```kotlin
httpListener(listenerConnection) {
    lifecycle = HttpListenerLifecycle.EAGER
    // Server starts as soon as flow starts
}
```

**Use when:**
- You need the endpoint available immediately
- Fail-fast behavior is desired
- No other infrastructure dependencies

### LAZY

`LAZY` registers the handler but does not call `HttpServer.start()`. Use it only when application infrastructure owns startup of the shared server:

```kotlin
httpListener(listenerConnection) {
    lifecycle = HttpListenerLifecycle.LAZY
}
```

## Examples

### REST API with Path Variables

```kotlin
val userApiFlow = flow("user-api") {
    httpListener(listenerConnection) {
        path = "/api/users/{id}"
        allowedMethods = listOf(HttpMethod.GET)
    }
    
    // Access path variables via attributes
    logger {
        message = "Fetching user: #[vars.attributes.uriParams.id]"
    }
    
    setPayload {
        value = """
            {
                "id": "#[vars.attributes.uriParams.id]",
                "name": "John Doe"
            }
        """.trimIndent()
    }
}
```

### Webhook Handler

```kotlin
val webhookFlow = flow("webhook-handler") {
    httpListener(listenerConnection) {
        path = "/webhooks/events"
        allowedMethods = listOf(HttpMethod.POST)
    }
    
    // Process webhook payload
    transform {
        setVariable("event", "#[payload]")
    }
    
    logger {
        message = "Received webhook: #[vars.event]"
    }
    
    // Acknowledge receipt
    setPayload {
        value = "{\"status\": \"received\"}"
    }
}
```

### Custom Headers and Correlation ID

```kotlin
val apiFlow = flow("api-with-headers") {
    httpListener(listenerConnection) {
        path = "/api/process"
        allowedMethods = listOf(HttpMethod.POST)
    }
    
    // Correlation ID is automatically extracted from X-Correlation-ID header
    // and propagated through the flow
    
    logger {
        message = "Processing request with correlation: #[vars.correlationId]"
    }
    
    // Access custom headers
    transform {
        setVariable("apiKey", "#[vars.attributes.headers['X-API-Key']]")
    }
}
```

### Error Handling

```kotlin
val apiFlow = flow("api-with-error-handling") {
    httpListener(listenerConnection) {
        path = "/api/data"
        allowedMethods = listOf(HttpMethod.GET)
        
        errorResponse {
            statusCode = "#[vars.httpStatus default 500]"
            body = """
                {
                    "error": "#[error.description]",
                    "timestamp": "#[now()]"
                }
            """.trimIndent()
        }
    }
    
    tryBlock {
        // Risky operation
        raiseError {
            description = "Database connection failed"
        }
    }
    catchBlock {
        // Handle error
        setVariable("httpStatus", 503)
    }
}
```

## Troubleshooting

### Service Stops Immediately

**Problem:** Application starts then exits immediately.

**Solution:** Ensure you're using `WebApplicationType.SERVLET`:

```kotlin
@MuseApplication(webEnvironment = WebApplicationType.SERVLET)
```

### Port Already in Use

**Problem:** `java.net.BindException: Address already in use`

**Solution:** Change the port in connection config:

```kotlin
connectionConfig = HttpListenerConnectionConfig(
    port = 8081  // Different port
)
```

### 404 Not Found

**Problem:** HTTP 404 error when accessing endpoint.

**Check:**
1. Path matches exactly (including leading `/`)
2. HTTP method is allowed
3. Application is running with SERVLET web environment

### Timeout Errors

**Problem:** Requests timeout after 30 seconds.

**Solution:** Configure timeout:

```kotlin
httpListener(listenerConnection) {
    timeoutMs = 60000  // 60 seconds
}
```

## Migration from Other Systems

### From MuleSoft

MuleSoft HTTP Listener → Muse HTTP Listener:

```xml
<!-- MuleSoft -->
<http:listener path="/api/hello" />
```

```kotlin
// Muse
httpListener(listenerConnection) {
    path = "/api/hello"
}
```

### From Spring Boot

Spring Boot Controller → Muse HTTP Listener:

```java
// Spring Boot
@GetMapping("/api/hello")
public String hello() {
    return "hello world";
}
```

```kotlin
// Muse
httpListener(listenerConnection) {
    path = "/api/hello"
    allowedMethods = listOf(HttpMethod.GET)
}
setPayload {
    value = "\"hello world\""
}
```

## Advanced Topics

### Custom Response Headers

```kotlin
httpListener(listenerConnection) {
    path = "/api/data"
    
    successResponse {
        header("X-Custom-Header", "value")
        header("Cache-Control", "no-cache")
    }
}
```

### Request Body Handling

```kotlin
httpListener(listenerConnection) {
    path = "/api/upload"
    allowedMethods = listOf(HttpMethod.POST)
}

// Access request body via payload
transform {
    setVariable("data", "#[payload]")
}
```

### Query Parameters

```kotlin
httpListener(listenerConnection) {
    path = "/api/search"
}

// Access query params via attributes
logger {
    message = "Search query: #[vars.attributes.queryParams.q]"
}
```

## References

- [ADR-017: HTTP Connector API and Adapter SPI](adr/ADR-017-http-listener-spi.md)
- [Architecture Documentation](../ARCHITECTURE.md)
- [DSL Reference](../muse-http-connector-api/src/main/kotlin/dev/museframework/http/api/dsl/HttpListenerDsl.kt)
