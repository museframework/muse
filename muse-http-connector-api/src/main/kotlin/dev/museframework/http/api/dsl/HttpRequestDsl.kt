package dev.museframework.http.api.dsl

import dev.museframework.dsl.api.dsl.PipelineOwner
import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.http.api.config.HttpRequestConnectionConfig

fun PipelineOwner.httpRequest(
    connectionConfig: ConfigRef,
    block: HttpRequestBuilder.() -> Unit,
) {
    processor(HttpRequestBuilder(connectionConfig).apply(block).build())
}

fun PipelineOwner.httpRequest(
    connectionConfig: HttpRequestConnectionConfig,
    block: HttpRequestBuilder.() -> Unit,
) {
    processor(HttpRequestBuilder(ConfigRef.ByReference(connectionConfig)).apply(block).build())
}

fun PipelineOwner.httpRequest(
    connectionConfig: () -> HttpRequestConnectionConfig,
    block: HttpRequestBuilder.() -> Unit,
) {
    processor(HttpRequestBuilder(ConfigRef.ByReference(connectionConfig())).apply(block).build())
}
