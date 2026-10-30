package dev.museframework.sftp.testkit

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedSftpServerTest {
    @Test
    fun `default construction binds an ephemeral port`() {
        Files.createTempDirectory("muse-sftp-testkit-ephemeral-").use { base ->
            EmbeddedSftpServer(base).use { server ->
                server.start()
                assertTrue(server.port > 0)
            }
        }
    }

    @Test
    fun `requested port binds exactly that port`() {
        val freePort = ServerSocket(0).use { it.localPort }
        Files.createTempDirectory("muse-sftp-testkit-requested-").use { base ->
            EmbeddedSftpServer(base, requestedPort = freePort).use { server ->
                server.start()
                assertEquals(freePort, server.port)
            }
        }
    }

    @Test
    fun `invalid requested port is rejected before start`() {
        Files.createTempDirectory("muse-sftp-testkit-invalid-").use { base ->
            assertThrows<IllegalArgumentException> {
                EmbeddedSftpServer(base, requestedPort = -1)
            }
            assertThrows<IllegalArgumentException> {
                EmbeddedSftpServer(base, requestedPort = 65_536)
            }
        }
    }

    @Test
    fun `occupied requested port fails to start`() {
        val freePort = ServerSocket(0).use { it.localPort }
        Files.createTempDirectory("muse-sftp-testkit-occupied-holder-").use { holderBase ->
            EmbeddedSftpServer(holderBase, requestedPort = freePort).use { holder ->
                holder.start()
                Files.createTempDirectory("muse-sftp-testkit-occupied-").use { base ->
                    EmbeddedSftpServer(base, requestedPort = freePort).use { server ->
                        assertThrows<Exception> { server.start() }
                    }
                }
            }
        }
    }

    @Test
    fun `close is idempotent`() {
        Files.createTempDirectory("muse-sftp-testkit-close-").use { base ->
            val server = EmbeddedSftpServer(base)
            server.start()
            server.close()
            server.close()
        }
    }

    @Test
    fun `activeSessionCount observes live sessions`() {
        Files.createTempDirectory("muse-sftp-testkit-sessions-").use { base ->
            EmbeddedSftpServer(base).use { server ->
                server.start()
                assertEquals(0, server.activeSessionCount)

                val client = SshClient.setUpDefaultClient()
                client.start()
                try {
                    val session = openSession(client, server)
                    try {
                        assertEquals(1, server.activeSessionCount)
                    } finally {
                        session.close()
                    }
                    awaitSessionDrain(server)
                    assertEquals(0, server.activeSessionCount)
                } finally {
                    client.stop()
                }
            }
        }
    }

    private fun openSession(
        client: SshClient,
        server: EmbeddedSftpServer,
    ): ClientSession {
        val session =
            client
                .connect(EmbeddedSftpServer.USERNAME, EmbeddedSftpServer.HOST, server.port)
                .verify(Duration.ofSeconds(5))
                .session
        session.addPasswordIdentity(EmbeddedSftpServer.PASSWORD)
        session.auth().verify(Duration.ofSeconds(5))
        return session
    }

    private fun awaitSessionDrain(server: EmbeddedSftpServer) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (server.activeSessionCount == 0) return
            Thread.sleep(25)
        }
        error("SFTP sessions did not drain; activeSessionCount=${server.activeSessionCount}")
    }

    private inline fun <T> java.nio.file.Path.use(block: (java.nio.file.Path) -> T): T =
        try {
            block(this)
        } finally {
            toFile().deleteRecursively()
        }
}
