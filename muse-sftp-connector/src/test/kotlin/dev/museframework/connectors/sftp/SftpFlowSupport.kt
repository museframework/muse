package dev.museframework.connectors.sftp

import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.dsl.sftpConnection
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.ServiceLoader

internal const val SFTP_FLOW_CONNECTION = "files"

internal fun sftpFlowConnection(server: EmbeddedSftpServer): SftpConnectionConfig =
    sftpConnection {
        name = SFTP_FLOW_CONNECTION
        host = EmbeddedSftpServer.HOST
        port = server.port
        username = EmbeddedSftpServer.USERNAME
        password = EmbeddedSftpServer.PASSWORD
        hostKeyChecking = SftpHostKeyChecking.INSECURE
        connectionTimeoutMs = 5_000
        authenticationTimeoutMs = 5_000
        operationTimeoutMs = 5_000
    }

internal suspend fun runSftpApplication(
    connection: SftpConnectionConfig,
    flow: MuseFlow.Flow,
    body: suspend () -> Unit,
) {
    val application = bootstrap(sftpApplicationConfig(connection, flow))
    application.init()
    try {
        application.start()
        body()
    } finally {
        application.stop()
    }
}

internal suspend fun initSftpApplication(
    connection: SftpConnectionConfig,
    flow: MuseFlow.Flow,
) {
    val application = bootstrap(sftpApplicationConfig(connection, flow))
    try {
        application.init()
    } finally {
        runCatching { application.stop() }
    }
}

internal suspend fun awaitTrue(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(20)
    }
}

private fun sftpApplicationConfig(
    connection: SftpConnectionConfig,
    flow: MuseFlow.Flow,
): MuseApplicationConfig {
    val factory = MuseComponentFactory()
    ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
    return MuseApplicationConfig(
        flows = listOf(flow),
        configs = listOf(connection),
        factory = factory,
    )
}
