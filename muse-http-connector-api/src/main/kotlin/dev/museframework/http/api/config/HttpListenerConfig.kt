package dev.museframework.http.api.config

import dev.museframework.dsl.api.model.component.Source
import dev.museframework.http.api.model.HttpMethod
import java.util.UUID

data class HttpListenerConfig(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val connectionConfigRef: ConnectionConfigRef,
    val path: String,
    val allowedMethods: List<HttpMethod> = listOf(HttpMethod.ANY),
    val timeoutMs: Long? = null,
    val successResponse: HttpResponseConfig = SuccessHttpResponseConfig(),
    val errorResponse: HttpResponseConfig = ErrorHttpResponseConfig(),
    val responseStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO,
    val lifecycle: HttpListenerLifecycle = HttpListenerLifecycle.EAGER,
) : Source {
    /**
     * Convenience accessor for the resolved connection config.
     * Only available when using [ConnectionConfigRef.ByReference].
     * For [ConnectionConfigRef.ByName], the config is resolved at init-time
     * by the [dev.museframework.http.api.source.HttpListenerSourceBuilder].
     */
    val connectionConfig: HttpListenerConnectionConfig
        get() = when (val ref = connectionConfigRef) {
            is ConnectionConfigRef.ByReference -> ref.config
            is ConnectionConfigRef.ByName ->
                error(
                    "ConnectionConfig '${ref.name}' is a ByName reference and has not been resolved yet. " +
                        "Resolution happens at init-time in the SourceBuilder.",
                )
        }
}
