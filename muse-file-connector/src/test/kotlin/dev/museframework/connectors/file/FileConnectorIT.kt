package dev.museframework.connectors.file

import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import dev.museframework.file.api.dsl.fileWrite
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileConnectorIT {
    @TempDir
    lateinit var workingDirectory: Path

    @Test
    fun `resolves file connection working directory from configuration properties at bootstrap`() {
        val propertiesFile = workingDirectory.resolve("application.properties")
        propertiesFile.writeText("output.dir=$workingDirectory\n")
        val connection = fileConnection {
            name = "output"
            workingDirectory = "\${output.dir}"
        }
        val factory = MuseComponentFactory()
        ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }

        bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                configs = listOf(connection, ConfigurationPropertiesConfig(docName = "", docId = "", file = "file:$propertiesFile")),
                factory = factory,
            ),
        )
    }

    @Test
    fun `service loaded connector streams a polled file through the engine and applies its post action`() = runBlocking {
        val incoming = Files.createDirectories(workingDirectory.resolve("incoming"))
        val connection =
            fileConnection {
                name = "files"
                workingDirectory = this@FileConnectorIT.workingDirectory.toString()
            }
        val fileFlow =
            flow("file-copy-flow") {
                fileListener("files") {
                    directory = "incoming"
                    pollingFrequencyMillis = 20
                    autoDelete = true
                }
                fileWrite(fileConnection("files")) {
                    path = "output/copied.txt"
                }
            }
        val factory = MuseComponentFactory()
        ServiceLoader.load(MuseComponentRegistrar::class.java).forEach { it.register(factory) }
        val application =
            bootstrap(
                MuseApplicationConfig(
                    flows = listOf(fileFlow),
                    configs = listOf(connection),
                    factory = factory,
                ),
            )

        application.init()
        val input = incoming.resolve("input.txt").also { it.writeText("connector-e2e") }
        try {
            application.start()
            val output = workingDirectory.resolve("output/copied.txt")
            withTimeout(5_000) {
                while (!output.exists() || input.exists()) delay(20)
            }

            assertEquals("connector-e2e", output.readText())
            assertFalse(input.exists())
        } finally {
            application.stop()
        }
    }
}
