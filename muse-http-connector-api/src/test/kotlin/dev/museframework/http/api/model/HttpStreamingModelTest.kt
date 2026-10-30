package dev.museframework.http.api.model

import dev.museframework.engine.api.streaming.RepeatableInMemoryCursorStreamProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpStreamingModelTest {

    @Test
    fun `http request can carry a streaming body provider`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val request = HttpRequest(
            method = HttpMethod.POST,
            requestPath = "/test",
            bodyProvider = provider,
        )

        assertNotNull(request.bodyProvider)
        assertEquals("abc", request.bodyProvider!!.openCursor().readBytes().decodeToString())
    }

    @Test
    fun `http response can carry a streaming body provider`() {
        val provider = RepeatableInMemoryCursorStreamProvider.fromBytes("abc".toByteArray())
        val response = HttpResponse(
            statusCode = 200,
            bodyProvider = provider,
        )

        assertNotNull(response.bodyProvider)
        assertEquals("abc", response.bodyProvider!!.openCursor().readBytes().decodeToString())
    }
}
