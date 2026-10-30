package dev.museframework.http.api.server

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpRequest
import dev.museframework.http.api.model.HttpResponse

interface HttpServer {
    suspend fun start()
    suspend fun stop()

    /**
     * Registers a request handler. The adapter releases the inbound body
     * provider after the handler returns unless the response contains that exact
     * provider, in which case it is released after response serialization.
     */
    fun register(
        path: String,
        methods: List<HttpMethod>,
        handler: suspend (HttpRequest) -> HttpResponse,
    )
    fun unregister(path: String, methods: List<HttpMethod>)
}
