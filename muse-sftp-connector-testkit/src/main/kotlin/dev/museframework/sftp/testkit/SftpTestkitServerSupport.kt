package dev.museframework.sftp.testkit

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

internal data class SftpTestkitServerAssembly(
    val home: Path,
    val server: SshServer,
    val acceptedPublicKeys: MutableSet<PublicKey>,
)

internal fun assembleSftpTestkitServer(
    baseDirectory: Path,
    requestedPort: Int,
    sftpSubsystemFactory: SftpSubsystemFactory,
): SftpTestkitServerAssembly {
    val home = Files.createDirectories(baseDirectory.resolve("home"))
    val acceptedPublicKeys = ConcurrentHashMap.newKeySet<PublicKey>()
    val hostKeyProvider = SimpleGeneratorHostKeyProvider(baseDirectory.resolve("host-key.ser"))
    val server =
        SshServer.setUpDefaultServer().apply {
            host = EmbeddedSftpServer.HOST
            port = requestedPort
            keyPairProvider = hostKeyProvider
            passwordAuthenticator =
                PasswordAuthenticator { username, password, _ ->
                    username == EmbeddedSftpServer.USERNAME && password == EmbeddedSftpServer.PASSWORD
                }
            publickeyAuthenticator =
                PublickeyAuthenticator { username, key, _ ->
                    username == EmbeddedSftpServer.USERNAME && key in acceptedPublicKeys
                }
            fileSystemFactory = VirtualFileSystemFactory(home)
            subsystemFactories = listOf(sftpSubsystemFactory)
        }
    return SftpTestkitServerAssembly(home, server, acceptedPublicKeys)
}

internal fun SftpTestkitServerAssembly.hostKeyProvider(): SimpleGeneratorHostKeyProvider =
    server.keyPairProvider as SimpleGeneratorHostKeyProvider
