package dev.museframework.babelfish.mappers.http

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import com.squareup.kotlinpoet.joinToCode
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.SourceCodeEmitter
import dev.museframework.babelfish.emitter.kotlinNumericLiteral
import dev.museframework.babelfish.emitter.kotlinStringLiteral
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

        val mapped = HttpListenerConfig(
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

        return element.attrs["doc:id"]?.let { mapped.copy(docId = it) } ?: mapped
    }
}

private val httpListenerConnection = MemberName("dev.museframework.http.api.dsl", "httpListenerConnection")
private val httpListener = MemberName("dev.museframework.http.api.dsl", "httpListener", isExtension = true)
private val listOfFunction = MemberName("kotlin.collections", "listOf")

object HttpListenerConnectionConfigCodeEmitter : ConfigCodeEmitter<HttpListenerConnectionConfig> {
    override fun emit(
        config: HttpListenerConnectionConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        add("%M(%L) {\n", httpListenerConnection, kotlinStringLiteral(config.name))
        indent()
        addStatement("docName = %L", kotlinStringLiteral(config.docName))
        addStatement("host = %L", kotlinStringLiteral(config.host))
        addStatement("port = %L", kotlinNumericLiteral(config.port))
        if (config.protocol != HttpProtocol.HTTP) {
            addStatement("protocol = %T.%N", HttpProtocol::class, config.protocol.name)
        }
        if (config.basePath != "/") {
            addStatement("basePath = %L", kotlinStringLiteral(config.basePath))
        }
        if (!config.usePersistentConnections) {
            addStatement("usePersistentConnections = false")
        }
        if (config.connectionIdleTimeoutMs != 40000L) {
            addStatement("connectionIdleTimeoutMs = %L", kotlinNumericLiteral(config.connectionIdleTimeoutMs))
        }
        if (config.readTimeoutMs != 30000L) {
            addStatement("readTimeoutMs = %L", kotlinNumericLiteral(config.readTimeoutMs))
        }
        unindent()
        add("}")
    }
}

object HttpListenerCodeEmitter : SourceCodeEmitter<HttpListenerConfig> {
    override fun emit(
        source: HttpListenerConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        val listenerArg = when (val ref = source.connectionConfigRef) {
            is ConnectionConfigRef.ByReference -> HttpListenerConnectionConfigCodeEmitter.emit(ref.config, ctx)
            is ConnectionConfigRef.ByName -> kotlinStringLiteral(ref.name)
        }

        beginControlFlow("%M(%L)", httpListener, listenerArg)
        addStatement("docId = %L", kotlinStringLiteral(source.docId))
        addStatement("path = %L", kotlinStringLiteral(source.path))
        if (source.allowedMethods != listOf(HttpMethod.ANY)) {
            val methods = source.allowedMethods.map { CodeBlock.of("%T.%N", HttpMethod::class, it.name) }
            addStatement("allowedMethods = %M(%L)", listOfFunction, methods.joinToCode())
        }
        if (source.responseStreamingMode != HttpStreamingMode.AUTO) {
            addStatement("responseStreamingMode = %T.%N", HttpStreamingMode::class, source.responseStreamingMode.name)
        }
        emitResponse("successResponse", source.successResponse, SuccessHttpResponseConfig())
        emitResponse("errorResponse", source.errorResponse, ErrorHttpResponseConfig())
        endControlFlow()
    }
}

private fun CodeBlock.Builder.emitResponse(
    blockName: String,
    response: HttpResponseConfig,
    defaults: HttpResponseConfig,
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

    beginControlFlow("%N", blockName)
    if (response.statusCode != defaults.statusCode) {
        addStatement("statusCode = %L", kotlinStringLiteral(response.statusCode))
    }
    response.reasonPhrase?.let { addStatement("reasonPhrase = %L", kotlinStringLiteral(it)) }
    response.headersExpression?.let { addStatement("headersExpression = %L", kotlinStringLiteral(it)) }
    response.headers.forEach { (name, value) ->
        addStatement("header(%L, %L)", kotlinStringLiteral(name), kotlinStringLiteral(value))
    }
    response.body?.let { addStatement("body = %L", kotlinStringLiteral(it)) }
    endControlFlow()
}

private inline fun <reified T : Enum<T>> String.toHttpEnum(): T = enumValueOf(trim().uppercase())

private fun RawElement.childText(tag: String): String? = children.find { it.tag == tag }?.textContent
