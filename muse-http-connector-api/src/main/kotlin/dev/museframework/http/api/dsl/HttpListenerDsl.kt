package dev.museframework.http.api.dsl

import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.dsl.builder.FlowBuilder

fun FlowBuilder.httpListener(
    connectionConfig: HttpListenerConnectionConfig,
    block: HttpListenerBuilder.() -> Unit,
) {
    source(HttpListenerBuilder(ConnectionConfigRef.ByReference(connectionConfig)).apply(block).build())
}

fun FlowBuilder.httpListener(
    connectionConfig: () -> HttpListenerConnectionConfig,  // <- lambda
    block: HttpListenerBuilder.() -> Unit,
) {
    source(HttpListenerBuilder(ConnectionConfigRef.ByReference(connectionConfig())).apply(block).build())
}

/**
 * Defines an HTTP listener by referencing the connection configuration by name.
 *
 * The [configName] must match the [dev.museframework.http.api.config.HttpListenerConnectionConfig.docName]
 * of a configuration registered in the Muse application context.
 * Resolution happens at init-time (fail-fast if not found).
 *
 * This mirrors the `flowRef("name")` pattern and is useful for generated code
 * (e.g. babelfish XML-to-Kotlin) or when the config is defined in a separate module.
 *
 * **Prefer the direct-reference overload when possible** — it is type-safe and compile-time checked.
 */
fun FlowBuilder.httpListener(
    configName: String,
    block: HttpListenerBuilder.() -> Unit,
) {
    source(HttpListenerBuilder(ConnectionConfigRef.ByName(configName)).apply(block).build())
}

