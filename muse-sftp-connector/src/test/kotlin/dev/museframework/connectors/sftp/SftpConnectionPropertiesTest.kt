package dev.museframework.connectors.sftp

import dev.museframework.sftp.api.config.SftpConnectionConfig
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SftpConnectionPropertiesTest {
    private fun connection() = SftpConnectionConfig(
        host = "\${host}",
        username = "\${user}",
        password = "\${password}",
        portExpression = "\${port}",
        workingDirectory = "/\${directory}",
        privateKeyPath = "\${key}",
        privateKeyPassphrase = "\${passphrase}",
        knownHostsFile = "\${knownHosts}",
    )

    private fun properties() = Properties().apply {
        setProperty("host", "localhost")
        setProperty("user", "muse")
        setProperty("password", "secret")
        setProperty("port", "2222")
        setProperty("directory", "upload")
        setProperty("key", "/keys/id_rsa")
        setProperty("passphrase", "phrase")
        setProperty("knownHosts", "/keys/known_hosts")
    }

    @Test
    fun `resolves all connection fields and preserves the original declaration`() {
        val original = connection()
        val resolved = resolveSftpConnectionProperties(original, properties())
        assertEquals("localhost", resolved.host)
        assertEquals("muse", resolved.username)
        assertEquals("secret", resolved.password)
        assertEquals(2222, resolved.port)
        assertNull(resolved.portExpression)
        assertEquals("/upload", resolved.workingDirectory)
        assertEquals("/keys/id_rsa", resolved.privateKeyPath)
        assertEquals("phrase", resolved.privateKeyPassphrase)
        assertEquals("/keys/known_hosts", resolved.knownHostsFile)
        assertEquals("\${host}", original.host)
    }

    @Test
    fun `substitutes property values once without interpreting secret contents`() {
        val secret = "dollar\$backslash\\\${literal}"
        val props = properties().apply { setProperty("password", secret) }
        assertEquals(secret, resolveSftpConnectionProperties(connection(), props).password)
    }

    @Test
    fun `missing credential property fails without exposing other secrets`() {
        val props = properties().apply { remove("passphrase") }
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveSftpConnectionProperties(connection(), props)
        }
        assertEquals("SFTP connection field 'privateKeyPassphrase' references missing property 'passphrase'", failure.message)
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun `invalid and missing ports never fall back to static port`() {
        for (value in listOf("not-a-port", "0", "65536")) {
            val props = properties().apply { setProperty("port", value) }
            assertFailsWith<IllegalArgumentException> { resolveSftpConnectionProperties(connection(), props) }
        }
        assertFailsWith<IllegalArgumentException> {
            resolveSftpConnectionProperties(connection(), properties().apply { remove("port") })
        }
    }

    @Test
    fun `static connections retain port and null optional authentication fields`() {
        val static = SftpConnectionConfig(host = "localhost", port = 2223, username = "muse", password = "secret")
        assertEquals(static, resolveSftpConnectionProperties(static, Properties()))
    }
}
