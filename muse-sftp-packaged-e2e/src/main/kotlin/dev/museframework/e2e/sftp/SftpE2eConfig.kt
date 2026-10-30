package dev.museframework.e2e.sftp

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.http.api.dsl.httpListener
import dev.museframework.http.api.model.HttpMethod
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.dsl.sftpConnection
import dev.museframework.sftp.api.dsl.sftpRead
import dev.museframework.sftp.api.dsl.sftpWrite
import dev.museframework.springboot.MuseConfigComponent
import org.springframework.core.env.Environment

@MuseConfigComponent
class SftpE2eConfig(
    environment: Environment,
) : MuseConfigDefinition() {
    val packagedSftpConnection =
        sftpConnection {
            name = "packaged-sftp"
            docName = "Packaged SFTP"
            host = environment.getProperty("packaged.sftp.host", "127.0.0.1")
            port = environment.getProperty("packaged.sftp.port", Int::class.java, 22)
            username = environment.getProperty("packaged.sftp.username", "muse")
            password = environment.getProperty("packaged.sftp.password", "secret")
            hostKeyChecking = SftpHostKeyChecking.INSECURE
            connectionTimeoutMs = 5_000
            authenticationTimeoutMs = 5_000
            operationTimeoutMs = 5_000
        }

    val packagedSftpRoundTripFlow =
        flow("packaged-sftp-round-trip") {
            httpListener("packaged-listener") {
                path = "/e2e/sftp"
                allowedMethods = listOf(HttpMethod.POST)
            }
            sftpWrite(packagedSftpConnection) {
                path = "/outbound/request.bin"
                createParentDirectories = true
            }
            sftpRead(packagedSftpConnection) {
                path = "/outbound/request.bin"
            }
        }
}
