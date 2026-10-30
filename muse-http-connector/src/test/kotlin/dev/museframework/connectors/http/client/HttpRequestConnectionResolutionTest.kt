package dev.museframework.connectors.http.client

import dev.museframework.http.api.config.HttpRequestConnectionConfig
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpRequestConnectionResolutionTest {
    @Test
    fun `resolves host and port from loaded configuration properties`() {
        val properties = Properties().apply {
            setProperty("receiver.host", "127.0.0.1")
            setProperty("receiver.target.port", "8082")
        }
        val config = HttpRequestConnectionConfig(
            name = "receiver",
            host = "\${receiver.host}",
            portExpression = "\${receiver.target.port}",
        )

        val resolved = resolveHttpRequestConnection(config, properties)

        assertEquals("127.0.0.1", resolved.host)
        assertEquals(8082, resolved.port)
    }

    @Test
    fun `fails clearly when the request port property is missing`() {
        val config = HttpRequestConnectionConfig(
            name = "receiver",
            host = "localhost",
            portExpression = "\${receiver.target.port}",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            resolveHttpRequestConnection(config, Properties())
        }
        assertEquals("HTTP connection 'receiver' has unresolved port '\${receiver.target.port}'", error.message)
    }
}
