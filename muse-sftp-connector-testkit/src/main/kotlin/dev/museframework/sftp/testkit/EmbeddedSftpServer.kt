package dev.museframework.sftp.testkit

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.SshServer
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddedSftpServer(
    baseDirectory: Path,
    requestedPort: Int = 0,
) : AutoCloseable {
    init {
        require(requestedPort in 0..65_535) {
            "requestedPort must be in 0..65535 but was $requestedPort"
        }
    }

    private val assembly =
        assembleSftpTestkitServer(
            baseDirectory = baseDirectory,
            requestedPort = requestedPort,
            sftpSubsystemFactory = SftpSubsystemFactory.Builder().build(),
        )
    private val server: SshServer = assembly.server
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val home: Path = assembly.home

    val port: Int
        get() = server.port

    val activeSessionCount: Int
        get() = server.activeSessions.size

    fun start() {
        check(!closed.get()) { "Embedded SFTP server is closed" }
        check(started.compareAndSet(false, true)) { "Embedded SFTP server is already started" }
        try {
            server.start()
        } catch (failure: Throwable) {
            started.set(false)
            throw failure
        }
    }

    fun allowPublicKey(key: PublicKey) {
        assembly.acceptedPublicKeys += key
    }

    fun writeKnownHosts(path: Path): Path {
        val publicKey = assembly.hostKeyProvider().loadKeys(null).first().public
        Files.writeString(path, "[$HOST]:$port ${PublicKeyEntry.toString(publicKey)}\n")
        return path
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && started.compareAndSet(true, false)) {
            server.stop(true)
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val USERNAME = "muse"
        const val PASSWORD = "secret"
    }
}
