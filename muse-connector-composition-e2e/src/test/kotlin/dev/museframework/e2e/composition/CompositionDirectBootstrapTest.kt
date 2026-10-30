package dev.museframework.e2e.composition

import dev.museframework.db.api.dsl.dbConfig
import dev.museframework.db.api.dsl.dbInsert
import dev.museframework.dsl.dsl.flow
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.dsl.sftpConnection
import dev.museframework.sftp.api.dsl.sftpWrite
import dev.museframework.sftp.testkit.ControllableSftpServer
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositionDirectBootstrapTest {
    @Test
    fun `direct bootstrap evaluates listener attributes like packaged file IT`() =
        runBlocking {
            val root = Files.createTempDirectory("composition-direct-bootstrap-")
            Files.createDirectories(root.resolve("incoming"))
            Files.createDirectories(root.resolve("archive"))
            val connection =
                fileConnection {
                    name = "composition-files"
                    workingDirectory = root.toAbsolutePath().toString()
                }
            val flow =
                flow("composition-direct-bootstrap") {
                    fileListener("composition-files") {
                        directory = "incoming"
                        pollingFrequencyMillis = 25
                        moveToDirectory = "archive"
                        overwrite = true
                        applyPostActionWhenFailed = false
                    }
                    fileWrite(connection) {
                        path = "output/name.txt"
                        content = "#[attributes.fileName]"
                    }
                }
            val factory = MuseComponentFactory()
            ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
            val application =
                bootstrap(
                    MuseApplicationConfig(
                        flows = listOf(flow),
                        configs = listOf(connection),
                        factory = factory,
                    ),
                )
            application.init()
            try {
                Files.writeString(root.resolve("incoming/input.txt"), "trigger")
                application.start()
                withTimeout(5_000) {
                    while (
                        !Files.exists(root.resolve("output/name.txt")) ||
                        Files.readString(root.resolve("output/name.txt")) != "input.txt" ||
                        !Files.exists(root.resolve("archive/input.txt")) ||
                        Files.exists(root.resolve("incoming/input.txt"))
                    ) {
                        delay(25)
                    }
                }
                assertEquals("input.txt", Files.readString(root.resolve("output/name.txt")))
                assertTrue(!Files.exists(root.resolve("incoming/input.txt")))
            } finally {
                application.stop()
            }
        }

    @Test
    fun `direct bootstrap inserts audit metadata from listener attributes`() =
        runBlocking {
            val root = Files.createTempDirectory("composition-direct-db-")
            Files.createDirectories(root.resolve("incoming"))
            Files.createDirectories(root.resolve("archive"))
            val database = H2AuditDatabase.start(root.resolve("h2"))
            try {
                val connection =
                    fileConnection {
                        name = "composition-files"
                        workingDirectory = root.toAbsolutePath().toString()
                    }
                val db =
                    dbConfig("composition-db") {
                        generic {
                            url = database.jdbcUrl
                            driverClassName = "org.h2.Driver"
                            user = database.user
                            password = database.password
                        }
                    }
                val flow =
                    flow("composition-direct-db") {
                        fileListener("composition-files") {
                            directory = "incoming"
                            pollingFrequencyMillis = 25
                            moveToDirectory = "archive"
                            overwrite = true
                            applyPostActionWhenFailed = false
                        }
                        dbInsert(db) {
                            sql = "INSERT INTO audit (id, byte_count) VALUES (:id, :size)"
                            inputParameters(
                                mapOf(
                                    "id" to "#[attributes.fileName]",
                                    "size" to "#[attributes.size]",
                                ),
                            )
                            target = "auditResult"
                        }
                    }
                val factory = MuseComponentFactory()
                ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
                val application =
                    bootstrap(
                        MuseApplicationConfig(
                            flows = listOf(flow),
                            configs = listOf(connection, db),
                            factory = factory,
                        ),
                    )
                application.init()
                try {
                    Files.writeString(root.resolve("incoming/input.txt"), "trigger")
                    application.start()
                    withTimeout(5_000) {
                        while (
                            database.rows() != listOf(AuditRow("input.txt", 7L)) ||
                            !Files.exists(root.resolve("archive/input.txt")) ||
                            Files.exists(root.resolve("incoming/input.txt"))
                        ) {
                            delay(25)
                        }
                    }
                    assertEquals(listOf(AuditRow("input.txt", 7L)), database.rows())
                } finally {
                    application.stop()
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `direct bootstrap runs file sftp and db with attribute-bound metadata before transfer`() =
        runBlocking {
            val root = Files.createTempDirectory("composition-direct-transfer-")
            Files.createDirectories(root.resolve("incoming"))
            Files.createDirectories(root.resolve("archive"))
            val database = H2AuditDatabase.start(root.resolve("h2"))
            val sftp = ControllableSftpServer(root.resolve("sftp"), requestedPort = 0)
            sftp.start()
            try {
                val fileConnection =
                    fileConnection {
                        name = "composition-files"
                        workingDirectory = root.toAbsolutePath().toString()
                    }
                val db =
                    dbConfig("composition-db") {
                        generic {
                            url = database.jdbcUrl
                            driverClassName = "org.h2.Driver"
                            user = database.user
                            password = database.password
                        }
                    }
                val sftpConnection =
                    sftpConnection {
                        name = "composition-sftp"
                        host = EmbeddedSftpServer.HOST
                        port = sftp.port
                        username = EmbeddedSftpServer.USERNAME
                        password = EmbeddedSftpServer.PASSWORD
                        hostKeyChecking = SftpHostKeyChecking.INSECURE
                    }
                val flow =
                    flow("composition-direct-transfer") {
                        fileListener("composition-files") {
                            directory = "incoming"
                            pollingFrequencyMillis = 25
                            moveToDirectory = "archive"
                            overwrite = true
                            applyPostActionWhenFailed = false
                            outputMimeType = "application/octet-stream"
                        }
                        sftpWrite(sftpConnection) {
                            path = "/#[attributes.fileName]"
                            createParentDirectories = true
                        }
                        dbInsert(db) {
                            sql = "INSERT INTO audit (id, byte_count) VALUES (:id, :size)"
                            inputParameters(
                                mapOf(
                                    "id" to "#[attributes.fileName]",
                                    "size" to "#[attributes.size]",
                                ),
                            )
                            target = "auditResult"
                        }
                    }
                val factory = MuseComponentFactory()
                ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
                val application =
                    bootstrap(
                        MuseApplicationConfig(
                            flows = listOf(flow),
                            configs = listOf(fileConnection, db, sftpConnection),
                            factory = factory,
                        ),
                    )
                application.init()
                try {
                    PayloadFiles.create(root.resolve("incoming/input.bin"), 7L)
                    application.start()
                    withTimeout(5_000) {
                        while (
                            database.rows() != listOf(AuditRow("input.bin", 7L)) ||
                            !Files.exists(root.resolve("archive/input.bin")) ||
                            Files.exists(root.resolve("incoming/input.bin"))
                        ) {
                            delay(25)
                        }
                    }
                    assertEquals(listOf(AuditRow("input.bin", 7L)), database.rows())
                    assertTrue(Files.exists(sftp.home.resolve("input.bin")))
                    assertTrue(Files.exists(root.resolve("archive/input.bin")))
                } finally {
                    application.stop()
                }
            } finally {
                sftp.close()
                database.close()
            }
        }
}
