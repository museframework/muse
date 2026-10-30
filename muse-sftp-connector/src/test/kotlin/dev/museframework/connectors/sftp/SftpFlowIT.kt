package dev.museframework.connectors.sftp

import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.forEach
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.dsl.sftpConnection
import dev.museframework.sftp.api.dsl.sftpListener
import dev.museframework.sftp.api.dsl.sftpRead
import dev.museframework.sftp.api.dsl.sftpWrite
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SftpFlowIT {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `named listener and direct write connection resolve properties over real SFTP`() = runBlocking {
        withServer { server ->
            server.home.resolve("inbound").createDirectories()
            server.home.resolve("inbound/trigger.txt").writeText("property-based transfer")
            val output = server.home.resolve("output/result.txt")
            val connection = sftpFlowConnection(server).copy(
                host = "\${sftp.host}",
                port = 22,
                portExpression = "\${sftp.port}",
                username = "\${sftp.user}",
                password = "\${sftp.password}",
            )
            val testFlow = flow("connection-properties") {
                sftpListener(SFTP_FLOW_CONNECTION) {
                    directory = "/inbound"
                    pollIntervalMs = 20
                    deleteAfterProcessing()
                }
                sftpWrite(connection) { path = "/output/result.txt" }
            }
            val factory = MuseComponentFactory()
            ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
            val properties = mapOf(
                "sftp.host" to EmbeddedSftpServer.HOST,
                "sftp.port" to server.port.toString(),
                "sftp.user" to EmbeddedSftpServer.USERNAME,
                "sftp.password" to EmbeddedSftpServer.PASSWORD,
            ).map { (key, value) -> GlobalProperty(docName = key, name = key, value = value) }
            val application = bootstrap(MuseApplicationConfig(
                flows = listOf(testFlow),
                configs = listOf(connection) + properties,
                factory = factory,
            ))
            try {
                application.init()
                application.start()
                awaitTrue { output.exists() && output.readText() == "property-based transfer" }
                awaitTrue { !server.home.resolve("inbound/trigger.txt").exists() }
            } finally {
                application.stop()
            }
        }
    }

    @Test
    fun `dynamic write mode overwrites the first item and appends the next over real SFTP`() = runBlocking {
        withServer { server ->
            server.home.resolve("inbound").createDirectories()
            val trigger = server.home.resolve("inbound/trigger.txt")
            trigger.writeText("trigger")
            val output = server.home.resolve("output/result.txt")
            val testFlow = flow("dynamic-write-mode") {
                sftpListener(SFTP_FLOW_CONNECTION) {
                    directory = "/inbound"
                    pollIntervalMs = 20
                    deleteAfterProcessing()
                }
                setPayload { value = "#[['first', 'second']]" }
                forEach {
                    setVariable("mode") { value = "#[if (vars.counter == 1) 'OVERWRITE' else 'APPEND']" }
                    sftpWrite(sftpConnection(SFTP_FLOW_CONNECTION)) {
                        path = "/output/result.txt"
                        modeExpression = "#[vars.mode]"
                    }
                }
            }
            runSftpApplication(sftpFlowConnection(server), testFlow) {
                awaitTrue { output.exists() && output.readText() == "firstsecond" }
                awaitTrue { !trigger.exists() }
            }
        }
    }

    @Test
    fun `missing connection name fails init before a socket`() =
        runBlocking {
            val connection =
                sftpConnection {
                    name = SFTP_FLOW_CONNECTION
                    host = EmbeddedSftpServer.HOST
                    port = 1
                    username = EmbeddedSftpServer.USERNAME
                    password = EmbeddedSftpServer.PASSWORD
                    hostKeyChecking = SftpHostKeyChecking.INSECURE
                }
            val flow =
                flow("missing-connection") {
                    sftpListener("absent") {
                        directory = "/inbound"
                        pollIntervalMs = 20
                    }
                    sftpRead(sftpConnection("absent")) {
                        path = "/missing.txt"
                    }
                }

            val failure = expectInitFailure(connection, flow)

            val missing = generateSequence(failure) { it.cause }.filterIsInstance<IllegalStateException>().first()
            assertEquals("SFTP connection config 'absent' not found", missing.message)
        }

    @Test
    fun `expression path to a missing file is handled as FILE_NOT_FOUND`() =
        runBlocking {
            withServer { server ->
                server.home.resolve("inbound").createDirectories()
                val trigger = server.home.resolve("inbound/trigger.txt")
                trigger.writeText("trigger")
                val seen = server.home.resolve("errors/seen.txt")
                val after = server.home.resolve("output/after.txt")
                val flow =
                    flow("missing-remote-file") {
                        sftpListener(SFTP_FLOW_CONNECTION) {
                            directory = "/inbound"
                            pollIntervalMs = 20
                            deleteAfterProcessing()
                        }
                        sftpRead(sftpConnection(SFTP_FLOW_CONNECTION)) {
                            path = "#['/missing.txt']"
                        }
                        sftpWrite(sftpConnection(SFTP_FLOW_CONNECTION)) {
                            path = "/output/after.txt"
                        }
                        errorHandler {
                            onErrorContinue(SftpErrorType.FILE_NOT_FOUND) {
                                sftpWrite(sftpConnection(SFTP_FLOW_CONNECTION)) {
                                    path = "/errors/seen.txt"
                                    content = "#[error.errorType.namespace ++ ':' ++ error.errorType.identifier]"
                                }
                            }
                        }
                    }

                runSftpApplication(sftpFlowConnection(server), flow) {
                    awaitTrue { seen.exists() }
                    assertEquals("SFTP:FILE_NOT_FOUND", seen.readText())
                    assertFalse(after.exists())
                    awaitTrue { !trigger.exists() }
                }
            }
        }

    @Test
    fun `ACCESS_DENIED handler does not recover a missing file`() =
        runBlocking {
            withServer { server ->
                server.home.resolve("inbound").createDirectories()
                val trigger = server.home.resolve("inbound/trigger.txt")
                trigger.writeText("trigger")
                val seen = server.home.resolve("errors/seen.txt")
                val after = server.home.resolve("output/after.txt")
                val flow =
                    flow("wrong-handler") {
                        sftpListener(SFTP_FLOW_CONNECTION) {
                            directory = "/inbound"
                            pollIntervalMs = 20
                            deleteAfterProcessing()
                        }
                        sftpRead(sftpConnection(SFTP_FLOW_CONNECTION)) {
                            path = "#['/missing.txt']"
                        }
                        sftpWrite(sftpConnection(SFTP_FLOW_CONNECTION)) {
                            path = "/output/after.txt"
                        }
                        errorHandler {
                            onErrorContinue(SftpErrorType.ACCESS_DENIED) {
                                sftpWrite(sftpConnection(SFTP_FLOW_CONNECTION)) {
                                    path = "/errors/seen.txt"
                                    content = "ACCESS_DENIED"
                                }
                            }
                        }
                    }

                runSftpApplication(sftpFlowConnection(server), flow) {
                    delay(1_500)
                    assertFalse(seen.exists())
                    assertFalse(after.exists())
                    assertTrue(trigger.exists())
                }
            }
        }

    private suspend fun withServer(body: suspend (EmbeddedSftpServer) -> Unit) {
        val server = EmbeddedSftpServer(temporaryDirectory.resolve("server-${System.nanoTime()}"))
        server.start()
        try {
            body(server)
        } finally {
            server.close()
        }
    }

    private suspend fun expectInitFailure(
        connection: SftpConnectionConfig,
        flow: MuseFlow.Flow,
    ): Throwable =
        try {
            initSftpApplication(connection, flow)
            throw AssertionError("Expected SFTP flow init to fail")
        } catch (failure: Throwable) {
            if (failure is AssertionError) throw failure
            failure
        }
}
