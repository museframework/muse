package dev.museframework.sftp.api.dsl

import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SftpConnectionDslTest {
    @Test
    fun `connection builder creates secure typed configuration`() {
        val config =
            sftpConnection {
                name = "warehouse"
                docName = "Warehouse SFTP"
                host = "sftp.example.test"
                username = "muse"
                password = "secret"
            }

        assertEquals("warehouse", config.name)
        assertEquals("Warehouse SFTP", config.docName)
        assertEquals("sftp.example.test", config.host)
        assertEquals(22, config.port)
        assertEquals("muse", config.username)
        assertEquals(SftpHostKeyChecking.STRICT, config.hostKeyChecking)
        assertEquals("~/.ssh/known_hosts", config.knownHostsFile)
        assertEquals("/", config.workingDirectory)
        assertEquals(10_000, config.connectionTimeoutMs)
        assertEquals(10_000, config.authenticationTimeoutMs)
        assertEquals(30_000, config.operationTimeoutMs)
        assertTrue(config.docId.isNotBlank())
        assertNotNull(config.sourceFile)
        assertNotNull(config.sourceLine)
        assertNotNull(config.sourceClass)
    }

    @Test
    fun `connection builder supports private key authentication and explicit source metadata`() {
        val config =
            sftpConnection {
                host = "127.0.0.1"
                port = 2222
                username = "key-user"
                privateKeyPath = "/keys/id_ed25519"
                privateKeyPassphrase = "key-secret"
                knownHostsFile = "/keys/known_hosts"
                workingDirectory = "/exchange"
                connectionTimeoutMs = 1_500
                authenticationTimeoutMs = 2_500
                operationTimeoutMs = 3_500
                sourceFile = "SftpConfig.kt"
                sourceLine = 42
                sourceClass = "example.SftpConfig"
            }

        assertEquals(2222, config.port)
        assertEquals("/keys/id_ed25519", config.privateKeyPath)
        assertEquals("key-secret", config.privateKeyPassphrase)
        assertEquals("/keys/known_hosts", config.knownHostsFile)
        assertEquals("/exchange", config.workingDirectory)
        assertEquals(1_500, config.connectionTimeoutMs)
        assertEquals(2_500, config.authenticationTimeoutMs)
        assertEquals(3_500, config.operationTimeoutMs)
        assertEquals("SftpConfig.kt", config.sourceFile)
        assertEquals(42, config.sourceLine)
        assertEquals("example.SftpConfig", config.sourceClass)
    }

    @Test
    fun `connection string representation redacts credentials`() {
        val config =
            SftpConnectionConfig(
                host = "localhost",
                username = "muse",
                password = "password-secret",
                privateKeyPath = "/keys/id",
                privateKeyPassphrase = "passphrase-secret",
                hostKeyChecking = SftpHostKeyChecking.INSECURE,
            )

        val rendered = config.toString()
        assertFalse("password-secret" in rendered)
        assertFalse("passphrase-secret" in rendered)
        assertTrue("<redacted>" in rendered)
    }

    @Test
    fun `connection helpers preserve typed references`() {
        val config = connection()

        assertEquals(SftpConnectionRef.ByReference(config), sftpConnection(config))
        assertEquals(SftpConnectionRef.ByName("warehouse"), sftpConnection("warehouse"))
    }

    @Test
    fun `connection rejects invalid endpoint authentication and timeout settings`() {
        assertFailsWith<IllegalArgumentException> {
            connection(host = "")
        }
        assertFailsWith<IllegalArgumentException> {
            connection(port = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            connection(password = null)
        }
        assertFailsWith<IllegalArgumentException> {
            connection(connectionTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            connection(hostKeyChecking = SftpHostKeyChecking.STRICT, knownHostsFile = "")
        }
    }

    private fun connection(
        host: String = "localhost",
        port: Int = 22,
        password: String? = "secret",
        connectionTimeoutMs: Long = 1_000,
        hostKeyChecking: SftpHostKeyChecking = SftpHostKeyChecking.INSECURE,
        knownHostsFile: String = "~/.ssh/known_hosts",
    ): SftpConnectionConfig =
        SftpConnectionConfig(
            host = host,
            port = port,
            username = "muse",
            password = password,
            connectionTimeoutMs = connectionTimeoutMs,
            hostKeyChecking = hostKeyChecking,
            knownHostsFile = knownHostsFile,
        )
}
