package dev.museframework.connectors.http.client

import dev.museframework.connectors.http.processor.HttpRequestMuseProcessor
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.http.api.client.HttpClientInitializer
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.engine.api.spi.MuseContext
import java.util.Properties

fun httpClientBuilder(httpClientInitializer: HttpClientInitializer): ProcessorBuilder<HttpRequestConfig> =
    ProcessorBuilder { component, context ->
        val resolvedConfig = resolveHttpRequestConnection(resolveConnectionConfig(component.connectionConfig, context), context.properties)
        HttpRequestMuseProcessor(
            component = component,
            config = resolvedConfig,
            requestConfig = component,
            client = httpClientInitializer.initialize(resolvedConfig),
            expressionEvaluator = context.expressionEvaluator,
        )
    }

private fun resolveConnectionConfig(
    configRef: ConfigRef,
    context: MuseContext,
): HttpRequestConnectionConfig {
    return when (configRef) {
        is ConfigRef.ByReference -> configRef.config as HttpRequestConnectionConfig
        is ConfigRef.ByName -> {
            context.properties[configRef.name] as? HttpRequestConnectionConfig
                ?: throw IllegalStateException(
                    "HTTP connection config '${configRef.name}' not found"
                )
        }
    }
}

private val propertyPlaceholder = Regex("""\$\{([^}]+)}""")

internal fun resolveHttpRequestConnection(
    config: HttpRequestConnectionConfig,
    properties: Properties,
): HttpRequestConnectionConfig {
    fun resolve(value: String): String =
        propertyPlaceholder.replace(value) { match ->
            properties.getProperty(match.groupValues[1]) ?: match.value
        }

    val host = resolve(config.host)
    require(!propertyPlaceholder.containsMatchIn(host)) {
        "HTTP connection '${config.name}' has unresolved host '$host'"
    }
    val port = config.portExpression?.let { expression ->
        val resolved = resolve(expression)
        resolved.toIntOrNull() ?: throw IllegalArgumentException(
            "HTTP connection '${config.name}' has unresolved port '$resolved'",
        )
    } ?: config.port

    return config.copy(host = host, port = port, portExpression = null)
}
