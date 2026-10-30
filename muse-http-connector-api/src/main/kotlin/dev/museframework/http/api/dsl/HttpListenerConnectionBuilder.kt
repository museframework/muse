package dev.museframework.http.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.http.api.config.HttpListenerConnectionConfig
import dev.museframework.http.api.model.HttpProtocol
import java.util.UUID

@MuseDsl
class HttpListenerConnectionBuilder(
    val name: String,
) {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var host: String = "0.0.0.0"
    var port: Int = 8080
    var protocol: HttpProtocol = HttpProtocol.HTTP
    var basePath: String = "/"
    var usePersistentConnections: Boolean = true
    var connectionIdleTimeoutMs: Long = 40000
    var readTimeoutMs: Long = 30000

    fun build(): HttpListenerConnectionConfig {
        val callSite = CallSiteCapture.capture()
        return HttpListenerConnectionConfig(
            name = name,
            docId = docId,
            docName = docName,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            host = host,
            port = port,
            protocol = protocol,
            basePath = basePath,
            usePersistentConnections = usePersistentConnections,
            connectionIdleTimeoutMs = connectionIdleTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
    }
}

fun httpListenerConnection(
    name: String,
    block: HttpListenerConnectionBuilder.() -> Unit,
): HttpListenerConnectionConfig = HttpListenerConnectionBuilder(name).apply(block).build()
