package dev.museframework.http.api.config

import dev.museframework.http.api.model.HttpProtocol
import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.util.UUID

data class HttpRequestConnectionConfig(
    val name: String = "",
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val host: String = "",
    val protocol: HttpProtocol = HttpProtocol.HTTP,
    val port: Int = protocol.defaultPort,
    val portExpression: String? = null,
    val basePath: String = "/",
    val connectionTimeoutMs: Long = 30000,
    val connectionIdleTimeoutMs: Long = 30000,
    val responseTimeoutMs: Long = 30000,
    val followRedirects: Boolean = true,
    val usePersistentConnections: Boolean = true,
    val maxConnections: Int = -1,
    val enableCookies: Boolean = true,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val defaultQueryParams: Map<String, String> = emptyMap(),
    val sendCorrelationId: SendCorrelationId = SendCorrelationId.AUTO,
    val sendBodyMode: HttpSendBodyMode = HttpSendBodyMode.AUTO,
    val requestStreamingMode: HttpStreamingMode = HttpStreamingMode.AUTO,
) : MuseConfiguration {
    init {
        require(port in 1..65535) { "port must be between 1 and 65535" }
        require(connectionTimeoutMs >= 0) { "connectionTimeoutMs must be >= 0" }
        require(connectionIdleTimeoutMs >= 0) { "connectionIdleTimeoutMs must be >= 0" }
        require(responseTimeoutMs >= 0) { "responseTimeoutMs must be >= 0" }
        require(maxConnections == -1 || maxConnections > 0) {
            "maxConnections must be -1 for unlimited connections or a positive value"
        }
    }
}
