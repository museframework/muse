package dev.museframework.http.api.source

import dev.museframework.engine.api.spi.MuseContext
import dev.museframework.engine.api.spi.SourceBuilder
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.server.HttpListenerServerInitializer

fun httpListenerSourceBuilder(initializer: HttpListenerServerInitializer) =
    SourceBuilder<HttpListenerConfig> { component, context, expressionEvaluator ->
        val resolvedConnectionConfig = resolveConnectionConfig(component, context)
        HttpListenerMuseSource(
            config = component,
            resolvedConnectionConfig = resolvedConnectionConfig,
            expressionEvaluator = expressionEvaluator,
            initializer = initializer,
            docName = "HttpListener",
            docId = "http-listener",
            docDescription = "HTTP listener source",
            sourceFile = component.sourceFile,
            sourceLine = component.sourceLine,
            sourceClass = component.sourceClass,
        )
    }

/**
 * Resolves the [HttpListenerConnectionConfig] from [HttpListenerConfig.connectionConfigRef].
 *
 * For [ConnectionConfigRef.ByReference]: returns the config directly.
 * For [ConnectionConfigRef.ByName]: looks up `context.properties["http.connection.<name>"]`,
 * which is stored by the [dev.museframework.engine.api.spi.ConfigBuilder] registered for
 * [HttpListenerConnectionConfig] during the bootstrap phase.
 */
internal fun resolveConnectionConfig(
    component: HttpListenerConfig,
    context: MuseContext,
): HttpListenerConnectionConfig =
    when (val ref = component.connectionConfigRef) {
        is ConnectionConfigRef.ByReference -> ref.config
        is ConnectionConfigRef.ByName -> {
            val key = "http.connection.${ref.name}"
            context.properties[key] as? HttpListenerConnectionConfig
                ?: error(
                    "No HttpListenerConnectionConfig named '${ref.name}' found. " +
                        "Make sure an HttpListenerConnectionConfig with docName = \"${ref.name}\" " +
                        "is registered in the application context.",
                )
        }
    }

