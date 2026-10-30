package dev.museframework.http.api.config

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.http.api.model.HttpProtocol
import java.util.UUID

data class HttpListenerConnectionConfig(
    val name: String,
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val protocol: HttpProtocol = HttpProtocol.HTTP,
    val basePath: String = "/",
    val usePersistentConnections: Boolean = true,
    val connectionIdleTimeoutMs: Long = 40000,
    val readTimeoutMs: Long = 30000,
) : MuseConfiguration {
    init {
        require(port in 0..65535) { "port must be between 0 and 65535" }
        require(connectionIdleTimeoutMs >= 0) { "connectionIdleTimeoutMs must be >= 0" }
        require(readTimeoutMs >= 0) { "readTimeoutMs must be >= 0" }
    }
}
