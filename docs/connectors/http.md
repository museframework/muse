# HTTP connector

The HTTP family provides outbound requests and inbound HTTP listeners. Its public DSL and connector runtime are separate from the Spring Boot and Quarkus adapters.

## Maven dependencies

Import the Muse BOM in your application's `dependencyManagement`:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-bom</artifactId>
    <version>0.1.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

For a Spring Boot application, the HTTP API, runtime and Spring adapter are available as:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-http-connector-api</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-http-connector</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-http-connector-spring</artifactId>
</dependency>
```

You also need the Muse host integration and the usual application dependencies. For concrete wiring, inspect [the Spring Boot example POM](../../examples/test-muse-spring-boot/pom.xml). Quarkus uses its own [HTTP adapter](../../muse-http-connector-quarkus/).

## Listener example

This excerpt defines a listener and a flow. Register it with a host application to receive requests:

```kotlin
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.dsl.httpListenerConnection
import dev.museframework.http.api.model.HttpMethod

val listener = httpListenerConnection("hello") {
    port = 8080
}

val hello = flow("hello-flow") {
    httpListener(listener) {
        path = "/api/hello"
        allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
    }
    setPayload { value = "\"hello world\"" }
}
```

For the [verified Spring example](../../examples/test-muse-spring-boot/), start the application and send `GET /api/hello`. The observed result on 2026-11-03 was HTTP 200, `Content-Type: text/plain`, with a body containing the literal quotes: `"hello world"`. The example includes host annotations and other flows; this excerpt is a DSL illustration, not a complete runnable project.

The family also exposes `httpRequestConnection` and `httpRequest` for outbound calls. See [the request DSL](../../muse-http-connector-api/src/main/kotlin/dev/museframework/http/api/dsl/HttpRequestDsl.kt).

## Verification and limits

- [Spring HTTP E2E test](../../examples/test-muse-spring-boot/src/test/kotlin/dev/museframework/test/e2e/HttpListenerE2ETest.kt) covers GET, POST, headers and unknown routes.
- [Spring HTTP client transport test](../../muse-http-connector-spring/src/test/kotlin/dev/museframework/connectors/http/spring/SpringHttpClientWireMockIT.kt) exercises outbound requests.
- [HTTP listener guide](../http-listener-guide.md) documents response configuration, lifecycle and streaming options; [streaming guide](../streaming-guide.md) covers ownership.

The HTTP family can be built through its own `muse-http/pom.xml`; the repository root `pom.xml` also aggregates that reactor. Runtime behavior varies by adapter. Verify HTTP status and error mappings for your application rather than assuming full Mule HTTP compatibility.
