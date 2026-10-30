package dev.museframework.http.api.processor

import dev.museframework.http.api.model.HttpResponse

interface HttpClient {
    suspend fun execute(request: HttpOutboundRequest): HttpResponse
    fun close()
}
