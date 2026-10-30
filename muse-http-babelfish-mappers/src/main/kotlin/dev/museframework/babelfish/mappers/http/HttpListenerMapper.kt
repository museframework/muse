package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.http.api.config.ConnectionConfigRef
import dev.museframework.http.api.config.ErrorHttpResponseConfig
import dev.museframework.http.api.config.HttpListenerConfig
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.config.HttpResponseConfig
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SuccessHttpResponseConfig
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol

object HttpListenerConnectionConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): HttpListenerConnectionConfig {
        val configName = config.attrs["name"] ?: config.id
        val connection = config.children.find { it.tag == "listener-connection" }

        return HttpListenerConnectionConfig(
            name = configName,
            docName = config.attrs["doc:name"] ?: configName,
            host = connection?.attrs?.get("host") ?: config.attrs["host"] ?: "0.0.0.0",
            port = connection?.attrs?.get("port")?.toIntOrNull() ?: config.attrs["port"]?.toIntOrNull() ?: 8080,
            protocol =
                connection?.attrs?.get("protocol")?.toHttpEnum<HttpProtocol>()
                    ?: HttpProtocol.HTTP,
            basePath = config.attrs["basePath"] ?: "/",
            usePersistentConnections =
                connection?.attrs?.get("usePersistentConnections")?.toBooleanStrictOrNull() ?: true,
            connectionIdleTimeoutMs =
                connection?.attrs?.get("connectionIdleTimeout")?.toLongOrNull() ?: 40000,
            readTimeoutMs = connection?.attrs?.get("readTimeout")?.toLongOrNull() ?: 30000,
        )
    }
}

object HttpListenerMapper : SourceMapper<HttpListenerConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): HttpListenerConfig {
        val methods =
            element.attrs["allowedMethods"]
                ?.split(",")
                ?.map { it.trim().toHttpEnum<HttpMethod>() }
                ?: listOf(HttpMethod.ANY)
        val successResponse = element.children.find { it.tag == "response" }
        val errorResponse = element.children.find { it.tag == "error-response" }

        return HttpListenerConfig(
            connectionConfigRef =
                ConnectionConfigRef.ByName(
                    element.requireAttr("config-ref", element.location),
                ),
            path = element.requireAttr("path", element.location),
            allowedMethods = methods,
            successResponse =
                successResponse?.let {
                    SuccessHttpResponseConfig(
                        statusCode = it.attrs["statusCode"] ?: "#[vars.httpStatus default 200]",
                        body = it.childText("body"),
                        reasonPhrase = it.attrs["reasonPhrase"],
                        headersExpression = it.childText("headers"),
                    )
                } ?: SuccessHttpResponseConfig(),
            errorResponse =
                errorResponse?.let {
                    ErrorHttpResponseConfig(
                        statusCode = it.attrs["statusCode"] ?: "#[vars.httpStatus default 500]",
                        body = it.childText("body"),
                        reasonPhrase = it.attrs["reasonPhrase"],
                        headersExpression = it.childText("headers"),
                    )
                } ?: ErrorHttpResponseConfig(),
            responseStreamingMode =
                element.attrs["responseStreamingMode"]?.toHttpEnum<HttpStreamingMode>()
                    ?: HttpStreamingMode.AUTO,
        )
    }
}

object HttpListenerConnectionConfigCodeEmitter : ConfigCodeEmitter<HttpListenerConnectionConfig> {
    override fun emit(
        config: HttpListenerConnectionConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.http.api.dsl.httpListenerConnection")
        ctx.addImport("dev.museframework.http.api.model.HttpProtocol")

        return buildString {
            appendLine("httpListenerConnection(\"${ctx.escapeKotlinString(config.name)}\") {")
            appendLine("    docName = \"${ctx.escapeKotlinString(config.docName)}\"")
            appendLine("    host = \"${ctx.escapeKotlinString(config.host)}\"")
            appendLine("    port = ${config.port}")
            if (config.protocol != HttpProtocol.HTTP) {
                appendLine("    protocol = HttpProtocol.${config.protocol}")
            }
            if (config.basePath != "/") {
                appendLine("    basePath = \"${ctx.escapeKotlinString(config.basePath)}\"")
            }
            if (!config.usePersistentConnections) {
                appendLine("    usePersistentConnections = false")
            }
            if (config.connectionIdleTimeoutMs != 40000L) {
                appendLine("    connectionIdleTimeoutMs = ${config.connectionIdleTimeoutMs}")
            }
            if (config.readTimeoutMs != 30000L) {
                appendLine("    readTimeoutMs = ${config.readTimeoutMs}")
            }
            append("}")
        }
    }
}

object HttpListenerCodeEmitter : SourceCodeEmitter<HttpListenerConfig> {
    override fun emit(
        source: HttpListenerConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.http.api.config.HttpStreamingMode")
        ctx.addImport("dev.museframework.http.api.dsl.httpListener")
        ctx.addImport("dev.museframework.http.api.model.HttpMethod")

        val listenerArg =
            when (val ref = source.connectionConfigRef) {
                is ConnectionConfigRef.ByReference ->
                    "\"${ctx.configValName(ref.config.docName)}\""
                is ConnectionConfigRef.ByName ->
                    "\"${ctx.escapeKotlinString(ref.name)}\""
            }

        return buildString {
            appendLine("httpListener($listenerArg) {")
            appendLine("    path = \"${ctx.escapeKotlinString(source.path)}\"")
            if (source.allowedMethods != listOf(HttpMethod.ANY)) {
                val methods = source.allowedMethods.joinToString(", ") { "HttpMethod.$it" }
                appendLine("    allowedMethods = listOf($methods)")
            }
            if (source.responseStreamingMode != HttpStreamingMode.AUTO) {
                appendLine("    responseStreamingMode = HttpStreamingMode.${source.responseStreamingMode}")
            }
            emitResponse(
                blockName = "successResponse",
                response = source.successResponse,
                defaults = SuccessHttpResponseConfig(),
                ctx = ctx,
            )
            emitResponse(
                blockName = "errorResponse",
                response = source.errorResponse,
                defaults = ErrorHttpResponseConfig(),
                ctx = ctx,
            )
            append("}")
        }
    }
}

private fun StringBuilder.emitResponse(
    blockName: String,
    response: HttpResponseConfig,
    defaults: HttpResponseConfig,
    ctx: CodegenContext,
) {
    if (
        response.statusCode == defaults.statusCode &&
        response.headers.isEmpty() &&
        response.body == null &&
        response.reasonPhrase == null &&
        response.headersExpression == null
    ) {
        return
    }

    appendLine("    $blockName {")
    if (response.statusCode != defaults.statusCode) {
        appendLine("        statusCode = \"${ctx.escapeKotlinString(response.statusCode)}\"")
    }
    response.reasonPhrase?.let {
        appendLine("        reasonPhrase = \"${ctx.escapeKotlinString(it)}\"")
    }
    response.headersExpression?.let {
        appendLine("        headersExpression = \"${ctx.escapeKotlinString(it)}\"")
    }
    response.headers.forEach { (name, value) ->
        appendLine("        header(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
    }
    response.body?.let {
        appendLine("        body = \"${ctx.escapeKotlinString(it)}\"")
    }
    appendLine("    }")
}

private inline fun <reified T : Enum<T>> String.toHttpEnum(): T = enumValueOf(trim().uppercase())

private fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent
