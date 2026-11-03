# APIKit connector

APIKit routes requests from an HTTP listener to named flows using an API specification. The runtime handles routing and request validation, with host-specific adapters for Spring Boot and Quarkus.

## Maven dependencies

With `dev.museframework:muse-bom:0.1.0` imported in `dependencyManagement`, add the API and runtime:

```xml
<dependency>
    <groupId>dev.museframework</groupId>
    <artifactId>muse-apikit-connector-api</artifactId>
</dependency>
<dependency>
    <groupId>dev.museframework.connectors</groupId>
    <artifactId>muse-apikit-connector</artifactId>
</dependency>
```

A Spring application can add `dev.museframework.connectors:muse-apikit-connector-spring`; a Quarkus application can add `dev.museframework.connectors:muse-apikit-connector-quarkus`. Both require appropriate Muse HTTP and host dependencies. Review the [Quarkus example POM](../../examples/test-muse-quarkus/pom.xml) for one complete dependency set.

## Routing example

This excerpt follows the [Quarkus test application's APIKit flow](../../examples/test-muse-quarkus/src/main/kotlin/dev/museframework/test/ApikitFlow.kt). The RAML specification must be on the application classpath:

```kotlin
import dev.museframework.apikit.api.dsl.apikit
import dev.museframework.apikit.api.dsl.apikitConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod

val apiConfig = apikitConfig {
    name = "api-config"
    spec = "api/api-spec.raml"
}

val apiMain = flow("apikit-main") {
    httpListener(httpListenerConfig) {
        path = "/apikit/*"
        allowedMethods = listOf(HttpMethod.GET, HttpMethod.POST)
    }
    apikit("api-config") {}
}
```

Here `httpListenerConfig` must be defined by the host application. The [example](../../examples/test-muse-quarkus/src/main/kotlin/dev/museframework/test/ApikitFlow.kt) also defines operation flows with names matching the RAML resource and method. This excerpt omits those operation flows and the host configuration; copying it alone will not give a working endpoint.

The [APIKit contract test](../../examples/test-muse-quarkus/src/test/kotlin/dev/museframework/test/apikit/ApikitContract.kt) expects `GET /apikit/hello` and JSON `POST /apikit/hello` to return HTTP 200 with `hello via apikit`. It expects the example's unknown APIKit route to return 500. These are source-level test assertions, not results from a newly executed Quarkus application.

## Verification and limitations

- [APIKit runtime tests](../../muse-apikit-connector/src/test/kotlin/dev/museframework/apikit/router/ApikitRoutingPolicyTest.kt) cover routing behavior.
- [Quarkus APIKit contract](../../examples/test-muse-quarkus/src/test/kotlin/dev/museframework/test/apikit/ApikitContract.kt) defines the application-level expected responses.
- [APIKit config builder](../../muse-apikit-connector-api/src/main/kotlin/dev/museframework/apikit/api/builder/ApikitConfigBuilder.kt) requires a configuration name and accepts a RAML `spec` path.

An APIKit Quarkus adapter exists, but the Maven plugin's Mule XML migration strategy explicitly rejects APIKit targeting Quarkus. The public Quarkus example also failed to resolve three Muse 0.1.0 Quarkus artifacts during the isolated promotion attempt on 2026-11-03. Do not advertise this as an independently verified public quick start until dependency resolution and application execution are reproduced.
