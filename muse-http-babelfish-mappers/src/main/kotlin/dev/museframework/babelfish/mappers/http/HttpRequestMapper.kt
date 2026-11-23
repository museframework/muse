package dev.museframework.babelfish.mappers.http

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.config.ConfigRef
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

private val httpRequestConnection = MemberName("dev.museframework.http.api.dsl", "httpRequestConnection")
private val httpRequest = MemberName("dev.museframework.http.api.dsl", "httpRequest", isExtension = true)

object HttpRequestConnectionConfigCodeEmitter : ConfigCodeEmitter<HttpRequestConnectionConfig> {
    override fun emit(
        config: HttpRequestConnectionConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        add("%M {\n", httpRequestConnection)
        indent()
        addStatement("name = %L", kotlinStringLiteral(config.name))
        addStatement("docName = %L", kotlinStringLiteral(config.docName))
        addStatement("host = %L", kotlinStringLiteral(config.host))
        if (config.protocol != HttpProtocol.HTTP) {
            addStatement("protocol = %T.%N", HttpProtocol::class, config.protocol.name)
        }
        addStatement("port = %L", config.port)
        config.portExpression?.let { addStatement("portExpression = %L", kotlinStringLiteral(it)) }
        if (config.basePath != "/") addStatement("basePath = %L", kotlinStringLiteral(config.basePath))
        if (config.connectionTimeoutMs != 30000L) {
            addStatement("connectionTimeoutMs = %L", config.connectionTimeoutMs)
        }
        if (config.connectionIdleTimeoutMs != 30000L) {
            addStatement("connectionIdleTimeoutMs = %L", config.connectionIdleTimeoutMs)
        }
        if (config.responseTimeoutMs != 30000L) addStatement("responseTimeoutMs = %L", config.responseTimeoutMs)
        if (!config.followRedirects) addStatement("followRedirects = false")
        if (!config.usePersistentConnections) addStatement("usePersistentConnections = false")
        if (config.maxConnections != -1) addStatement("maxConnections = %L", config.maxConnections)
        if (!config.enableCookies) addStatement("enableCookies = false")
        if (config.sendCorrelationId != SendCorrelationId.AUTO) {
            addStatement("sendCorrelationId = %T.%N", SendCorrelationId::class, config.sendCorrelationId.name)
        }
        if (config.sendBodyMode != HttpSendBodyMode.AUTO) {
            addStatement("sendBodyMode = %T.%N", HttpSendBodyMode::class, config.sendBodyMode.name)
        }
        if (config.requestStreamingMode != HttpStreamingMode.AUTO) {
            addStatement("requestStreamingMode = %T.%N", HttpStreamingMode::class, config.requestStreamingMode.name)
        }
        config.defaultHeaders.forEach { (name, value) ->
            addStatement("defaultHeader(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
        }
        config.defaultQueryParams.forEach { (name, value) ->
            addStatement("defaultQueryParam(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
        }
        unindent()
        add("}")
    }
}

object HttpRequestCodeEmitter : ElementCodeEmitter<HttpRequestConfig> {
    override fun emit(
        element: HttpRequestConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        val configRef = when (val ref = element.connectionConfig) {
            is ConfigRef.ByReference ->
                HttpRequestConnectionConfigCodeEmitter.emit(ref.config as HttpRequestConnectionConfig, ctx)
            is ConfigRef.ByName -> CodeBlock.of("%M(%L)", httpRequestConnection, kotlinStringLiteral(ref.name))
        }

        beginControlFlow("%M(%L)", httpRequest, configRef)
        val url = element.url
        if (url != null) {
            addStatement("url = %L", kotlinStringLiteral(url))
        } else {
            addStatement("path = %L", kotlinStringLiteral(element.path))
        }
        if (element.method != HttpMethod.GET) {
            addStatement("method = %T.%N", HttpMethod::class, element.method.name)
        }
        element.headers.forEach { (name, value) ->
            addStatement("header(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
        }
        element.queryParams.forEach { (name, value) ->
            addStatement("queryParam(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
        }
        element.uriParams.forEach { (name, value) ->
            addStatement("uriParam(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
        }
        element.headersExpression?.let { addStatement("headersExpression = %L", kotlinStringLiteral(it)) }
        element.queryParamsExpression?.let { addStatement("queryParamsExpression = %L", kotlinStringLiteral(it)) }
        element.uriParamsExpression?.let { addStatement("uriParamsExpression = %L", kotlinStringLiteral(it)) }
        element.body?.let { addStatement("body = %L", kotlinStringLiteral(it)) }
        element.responseTimeoutMs?.let { addStatement("responseTimeoutMs = %L", it) }
        element.followRedirects?.let { addStatement("followRedirects = %L", it) }
        element.sendBodyMode?.let { addStatement("sendBodyMode = %T.%N", HttpSendBodyMode::class, it.name) }
        element.requestStreamingMode?.let { addStatement("requestStreamingMode = %T.%N", HttpStreamingMode::class, it.name) }
        element.sendCorrelationId?.let { addStatement("sendCorrelationId = %T.%N", SendCorrelationId::class, it.name) }
        element.correlationId?.let { addStatement("correlationId = %L", kotlinStringLiteral(it)) }
        element.targetConfig?.let { target ->
            val name = kotlinStringLiteral(target.target)
            val expression = when (val value = target.targetValue) {
                "#[message]" -> "#[{payload: payload, attributes: attributes}]"
                else -> value
            }
            if (expression == null) {
                addStatement("targetConfig = %T(%L)", TargetConfig::class, name)
            } else {
                addStatement("targetConfig = %T(%L, %L)", TargetConfig::class, name, kotlinStringLiteral(expression))
            }
        }
        endControlFlow()
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
