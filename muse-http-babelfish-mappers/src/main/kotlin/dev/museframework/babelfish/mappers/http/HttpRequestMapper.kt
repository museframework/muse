package dev.museframework.babelfish.mappers.http

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.http.api.config.HttpRequestConfig
import dev.museframework.http.api.config.HttpRequestConnectionConfig
import dev.museframework.http.api.config.HttpSendBodyMode
import dev.museframework.http.api.config.HttpStreamingMode
import dev.museframework.http.api.config.SendCorrelationId
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpProtocol

object HttpRequestConnectionConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): HttpRequestConnectionConfig {
        val configName = config.attrs["name"] ?: config.id
        val connection = config.children.find { it.tag == "request-connection" }
        val protocol = connection?.attrs?.get("protocol")?.toEnum<HttpProtocol>() ?: HttpProtocol.HTTP
        val rawPort = connection?.attrs?.get("port")

        return HttpRequestConnectionConfig(
            name = configName,
            docName = config.attrs["doc:name"] ?: configName,
            host = connection?.attrs?.get("host") ?: config.attrs["host"].orEmpty(),
            protocol = protocol,
            port = rawPort?.toIntOrNull() ?: protocol.defaultPort,
            portExpression = rawPort?.takeIf { it.toIntOrNull() == null },
            basePath = config.attrs["basePath"] ?: "/",
            connectionIdleTimeoutMs =
                connection?.attrs?.get("connectionIdleTimeout")?.toLongOrNull() ?: 30000,
            responseTimeoutMs = config.attrs["responseTimeout"]?.toLongOrNull() ?: 30000,
            followRedirects = config.attrs["followRedirects"]?.toBooleanStrictOrNull() ?: true,
            usePersistentConnections =
                connection?.attrs?.get("usePersistentConnections")?.toBooleanStrictOrNull() ?: true,
            maxConnections = connection?.attrs?.get("maxConnections")?.toIntOrNull() ?: -1,
            enableCookies = config.attrs["enableCookies"]?.toBooleanStrictOrNull() ?: true,
            defaultHeaders = config.keyValueChildren("default-headers", "default-header"),
            defaultQueryParams = config.keyValueChildren("default-query-params", "query-param"),
            sendCorrelationId = config.attrs["sendCorrelationId"]?.toEnum<SendCorrelationId>() ?: SendCorrelationId.AUTO,
            sendBodyMode = config.attrs["sendBodyMode"]?.toEnum<HttpSendBodyMode>() ?: HttpSendBodyMode.AUTO,
            requestStreamingMode =
                config.attrs["requestStreamingMode"]?.toEnum<HttpStreamingMode>() ?: HttpStreamingMode.AUTO,
        )
    }
}

object HttpRequestMapper : ElementMapper<HttpRequestConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): HttpRequestConfig =
        HttpRequestConfig(
            connectionConfig = ConfigRef.ByName(element.requireAttr("config-ref", element.location)),
            path = element.attrs["path"] ?: "/",
            url = element.attrs["url"],
            method = element.attrs["method"]?.toEnum<HttpMethod>() ?: HttpMethod.GET,
            headersExpression = element.childText("headers"),
            queryParamsExpression = element.childText("query-params"),
            uriParamsExpression = element.childText("uri-params"),
            body = element.childText("body"),
            responseTimeoutMs = element.attrs["responseTimeout"]?.toLongOrNull(),
            followRedirects = element.attrs["followRedirects"]?.toBooleanStrictOrNull(),
            sendBodyMode = element.attrs["sendBodyMode"]?.toEnum<HttpSendBodyMode>(),
            requestStreamingMode = element.attrs["requestStreamingMode"]?.toEnum<HttpStreamingMode>(),
            sendCorrelationId = element.attrs["sendCorrelationId"]?.toEnum<SendCorrelationId>(),
            correlationId = element.attrs["correlationId"],
            targetConfig = element.attrs["target"]?.let { TargetConfig(it, element.attrs["targetValue"]) },
        )
}

object HttpRequestConnectionConfigCodeEmitter : ConfigCodeEmitter<HttpRequestConnectionConfig> {
    override fun emit(
        config: HttpRequestConnectionConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.http.api.config.HttpSendBodyMode")
        ctx.addImport("dev.museframework.http.api.config.HttpStreamingMode")
        ctx.addImport("dev.museframework.http.api.config.SendCorrelationId")
        ctx.addImport("dev.museframework.http.api.dsl.httpRequestConnection")
        ctx.addImport("dev.museframework.http.api.model.HttpProtocol")
        return buildString {
            appendLine("httpRequestConnection {")
            appendLine("    name = \"${ctx.escapeKotlinString(config.name)}\"")
            appendLine("    docName = \"${ctx.escapeKotlinString(config.docName)}\"")
            appendLine("    host = \"${ctx.escapeKotlinString(config.host)}\"")
            if (config.protocol != HttpProtocol.HTTP) appendLine("    protocol = HttpProtocol.${config.protocol}")
            appendLine("    port = ${config.port}")
            config.portExpression?.let { appendLine("    portExpression = \"${ctx.escapeKotlinString(it)}\"") }
            if (config.basePath != "/") appendLine("    basePath = \"${ctx.escapeKotlinString(config.basePath)}\"")
            if (config.connectionTimeoutMs != 30000L) appendLine("    connectionTimeoutMs = ${config.connectionTimeoutMs}")
            if (config.connectionIdleTimeoutMs != 30000L) {
                appendLine("    connectionIdleTimeoutMs = ${config.connectionIdleTimeoutMs}")
            }
            if (config.responseTimeoutMs != 30000L) appendLine("    responseTimeoutMs = ${config.responseTimeoutMs}")
            if (!config.followRedirects) appendLine("    followRedirects = false")
            if (!config.usePersistentConnections) appendLine("    usePersistentConnections = false")
            if (config.maxConnections != -1) appendLine("    maxConnections = ${config.maxConnections}")
            if (!config.enableCookies) appendLine("    enableCookies = false")
            if (config.sendCorrelationId != SendCorrelationId.AUTO) {
                appendLine("    sendCorrelationId = SendCorrelationId.${config.sendCorrelationId}")
            }
            if (config.sendBodyMode != HttpSendBodyMode.AUTO) {
                appendLine("    sendBodyMode = HttpSendBodyMode.${config.sendBodyMode}")
            }
            if (config.requestStreamingMode != HttpStreamingMode.AUTO) {
                appendLine("    requestStreamingMode = HttpStreamingMode.${config.requestStreamingMode}")
            }
            config.defaultHeaders.forEach { (name, value) ->
                appendLine("    defaultHeader(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
            }
            config.defaultQueryParams.forEach { (name, value) ->
                appendLine("    defaultQueryParam(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
            }
            append("}")
        }
    }
}

object HttpRequestCodeEmitter : ElementCodeEmitter<HttpRequestConfig> {
    override fun emit(
        element: HttpRequestConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.http.api.config.HttpSendBodyMode")
        ctx.addImport("dev.museframework.http.api.config.HttpStreamingMode")
        ctx.addImport("dev.museframework.http.api.config.SendCorrelationId")
        ctx.addImport("dev.museframework.http.api.dsl.httpRequest")
        ctx.addImport("dev.museframework.http.api.dsl.httpRequestConnection")
        ctx.addImport("dev.museframework.http.api.model.HttpMethod")
        element.targetConfig?.let { ctx.addImport("dev.museframework.dsl.api.config.TargetConfig") }

        val configRef =
            when (val ref = element.connectionConfig) {
                is ConfigRef.ByReference ->
                    HttpRequestConnectionConfigCodeEmitter.emit(ref.config as HttpRequestConnectionConfig, ctx)
                is ConfigRef.ByName -> "httpRequestConnection(\"${ctx.escapeKotlinString(ref.name)}\")"
            }

        return buildString {
            appendLine("httpRequest($configRef) {")
            element.url?.let { url ->
                appendLine("    url = \"${ctx.escapeKotlinString(url)}\"")
            } ?: appendLine("    path = \"${ctx.escapeKotlinString(element.path)}\"")
            if (element.method != HttpMethod.GET) appendLine("    method = HttpMethod.${element.method}")
            element.headers.forEach { (name, value) ->
                appendLine("    header(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
            }
            element.queryParams.forEach { (name, value) ->
                appendLine("    queryParam(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
            }
            element.uriParams.forEach { (name, value) ->
                appendLine("    uriParam(\"${ctx.escapeKotlinString(name)}\", \"${ctx.escapeKotlinString(value)}\")")
            }
            element.headersExpression?.let {
                appendLine("    headersExpression = \"${ctx.escapeKotlinString(it)}\"")
            }
            element.queryParamsExpression?.let {
                appendLine("    queryParamsExpression = \"${ctx.escapeKotlinString(it)}\"")
            }
            element.uriParamsExpression?.let {
                appendLine("    uriParamsExpression = \"${ctx.escapeKotlinString(it)}\"")
            }
            element.body?.let { appendLine("    body = \"${ctx.escapeKotlinString(it)}\"") }
            element.responseTimeoutMs?.let { appendLine("    responseTimeoutMs = $it") }
            element.followRedirects?.let { appendLine("    followRedirects = $it") }
            element.sendBodyMode?.let { appendLine("    sendBodyMode = HttpSendBodyMode.$it") }
            element.requestStreamingMode?.let { appendLine("    requestStreamingMode = HttpStreamingMode.$it") }
            element.sendCorrelationId?.let { appendLine("    sendCorrelationId = SendCorrelationId.$it") }
            element.correlationId?.let {
                appendLine("    correlationId = \"${ctx.escapeKotlinString(it)}\"")
            }
            element.targetConfig?.let { target ->
                append("    targetConfig = TargetConfig(\"${ctx.escapeKotlinString(target.target)}\"")
                target.targetValue?.let { value ->
                    val expression =
                        if (value == "#[message]") "#[{payload: payload, attributes: attributes}]" else value
                    append(", \"${ctx.escapeKotlinString(expression)}\"")
                }
                appendLine(")")
            }
            append("}")
        }
    }
}

private inline fun <reified T : Enum<T>> String.toEnum(): T = enumValueOf(trim().uppercase())

private fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent

private fun RawConfig.keyValueChildren(
    groupTag: String,
    itemTag: String,
): Map<String, String> =
    children
        .find { it.tag == groupTag }
        ?.children
        ?.asSequence()
        ?.filter { it.tag == itemTag }
        ?.mapNotNull { child ->
            val key = child.attrs["key"] ?: return@mapNotNull null
            val value = child.attrs["value"] ?: return@mapNotNull null
            key to value
        }?.toMap()
        .orEmpty()
