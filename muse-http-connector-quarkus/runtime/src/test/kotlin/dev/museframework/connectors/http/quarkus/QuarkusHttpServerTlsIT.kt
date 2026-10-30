package dev.museframework.connectors.http.quarkus

import dev.museframework.http.api.model.HttpMethod
import dev.museframework.http.api.model.HttpResponse
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.ClientAuth
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.Router
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarkusHttpServerTlsIT {
    private lateinit var vertx: Vertx
    private lateinit var adapter: QuarkusHttpServer
    private lateinit var tlsTransport: io.vertx.core.http.HttpServer
    private lateinit var plainTransport: io.vertx.core.http.HttpServer
    private lateinit var tlsClient: HttpClient
    private lateinit var plainClient: HttpClient
    private lateinit var expectedClientCertificate: X509Certificate

    @Volatile
    private var capturedCertificate: X509Certificate? = null

    @BeforeAll
    fun startTransports() {
        vertx = Vertx.vertx()
        val router = Router.router(vertx)
        adapter = QuarkusHttpServer().also { it.router = router }

        expectedClientCertificate = loadCertificate("tls/client-cert.pem")

        adapter.register("/tls/client-certificate", listOf(HttpMethod.GET)) { request ->
            capturedCertificate = request.clientCertificate
            HttpResponse(statusCode = 200)
        }

        val serverOptions = HttpServerOptions()
            .setSsl(true)
            .setKeyCertOptions(
                PemKeyCertOptions()
                    .setCertValue(resourceBuffer("tls/server-cert.pem"))
                    .setKeyValue(resourceBuffer("tls/server-key.pem")),
            )
            .setTrustOptions(PemTrustOptions().addCertValue(resourceBuffer("tls/client-cert.pem")))
            .setClientAuth(ClientAuth.REQUIRED)

        tlsTransport = vertx.createHttpServer(serverOptions)
            .requestHandler(router)
            .listen(0, LOOPBACK)
            .await()

        plainTransport = vertx.createHttpServer()
            .requestHandler(router)
            .listen(0, LOOPBACK)
            .await()

        val clientOptions = HttpClientOptions()
            .setSsl(true)
            .setVerifyHost(false)
            .setKeyCertOptions(
                PemKeyCertOptions()
                    .setCertValue(resourceBuffer("tls/client-cert.pem"))
                    .setKeyValue(resourceBuffer("tls/client-key.pem")),
            )
            .setTrustOptions(PemTrustOptions().addCertValue(resourceBuffer("tls/server-cert.pem")))

        tlsClient = vertx.createHttpClient(clientOptions)
        plainClient = vertx.createHttpClient()
    }

    @AfterAll
    fun stopTransports() {
        if (::adapter.isInitialized) runBlocking { adapter.stop() }
        if (::tlsClient.isInitialized) tlsClient.close()
        if (::plainClient.isInitialized) plainClient.close()
        if (::tlsTransport.isInitialized) tlsTransport.close().await()
        if (::plainTransport.isInitialized) plainTransport.close().await()
        if (::vertx.isInitialized) vertx.close().await()
    }

    @Test
    fun `client certificate is exposed for mTLS requests`() {
        val response = tlsClient
            .request(io.vertx.core.http.HttpMethod.GET, tlsTransport.actualPort(), LOOPBACK, "/tls/client-certificate")
            .compose { it.send() }
            .await()

        assertEquals(200, response.statusCode())

        val certificate = capturedCertificate ?: fail("Expected client certificate to be present")
        assertEquals("CN=muse-http-test-client", certificate.subjectX500Principal.name)
        assertArrayEquals(expectedClientCertificate.encoded, certificate.encoded)
    }

    @Test
    fun `plain HTTP requests have no client certificate`() {
        capturedCertificate = expectedClientCertificate // ensure a change is observable if not reset

        val response = plainClient
            .request(io.vertx.core.http.HttpMethod.GET, plainTransport.actualPort(), LOOPBACK, "/tls/client-certificate")
            .compose { it.send() }
            .await()

        assertEquals(200, response.statusCode())
        assertNull(capturedCertificate)
    }

    private fun loadCertificate(path: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return javaClass.classLoader.getResourceAsStream(path)!!.use {
            factory.generateCertificate(it) as X509Certificate
        }
    }

    private fun resourceBuffer(path: String): Buffer {
        val url = javaClass.classLoader.getResource(path) ?: error("Missing resource: $path")
        return Buffer.buffer(url.readBytes())
    }

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CLOSE_TIMEOUT_SECONDS = 10L
    }
}
