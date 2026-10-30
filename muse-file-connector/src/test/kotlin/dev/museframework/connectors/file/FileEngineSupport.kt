package dev.museframework.connectors.file

import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.dsl.builder.FlowBuilder
import dev.museframework.engine.api.spi.MuseComponentRegistrar
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.dsl.FileListenerBuilder
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.ServiceLoader
import kotlin.io.path.writeText

internal const val FILE_CONNECTION_NAME = "files"

internal fun fileEngineConnection(directory: Path) =
    fileConnection {
        name = FILE_CONNECTION_NAME
        workingDirectory = directory.toString()
    }

internal fun FlowBuilder.pollingFileListener(
    directory: String = "incoming",
    recursive: Boolean = false,
    configure: FileListenerBuilder.() -> Unit = {},
) {
    fileListener(FILE_CONNECTION_NAME) {
        this.directory = directory
        this.recursive = recursive
        pollingFrequencyMillis = 20
        applyPostActionWhenFailed = false
        configure()
    }
}

internal fun writeRelative(workingDirectory: Path, relativePath: String, text: String): Path {
    val path = workingDirectory.resolve(relativePath)
    Files.createDirectories(path.parent)
    path.writeText(text)
    return path
}

internal fun assignCreationTime(path: Path, instant: Instant): Instant? = assignTimestamp(path, creationTime = instant)

internal fun assignAccessTime(path: Path, instant: Instant): Instant? = assignTimestamp(path, lastAccessTime = instant)

internal fun supportsSymbolicLinks(directory: Path): Boolean {
    val probe = Files.createTempDirectory(directory, "symlink-probe")
    return try {
        val target = probe.resolve("target.txt").also { it.writeText("target") }
        val link = probe.resolve("link.txt")
        Files.createSymbolicLink(link, target.fileName)
        Files.isSymbolicLink(link)
    } catch (_: Exception) {
        false
    } finally {
        probe.toFile().deleteRecursively()
    }
}

private fun assignTimestamp(
    path: Path,
    creationTime: Instant? = null,
    lastAccessTime: Instant? = null,
): Instant? {
    return try {
        val view = Files.getFileAttributeView(path, BasicFileAttributeView::class.java) ?: return null
        view.setTimes(null, lastAccessTime?.let(FileTime::from), creationTime?.let(FileTime::from))
        val actual = Files.readAttributes(path, BasicFileAttributes::class.java)
        val recorded =
            when {
                creationTime != null -> actual.creationTime().toInstant()
                lastAccessTime != null -> actual.lastAccessTime().toInstant()
                else -> return null
            }
        val requested = creationTime ?: lastAccessTime ?: return null
        recorded.takeIf { it.epochSecond == requested.epochSecond }
    } catch (_: Exception) {
        null
    }
}

internal suspend fun runFileApplication(
    workingDirectory: Path,
    flow: MuseFlow.Flow,
    connection: FileConnectionConfig = fileEngineConnection(workingDirectory),
    body: suspend () -> Unit,
) {
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
        application.start()
        body()
    } finally {
        application.stop()
    }
}

internal suspend fun awaitTrue(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(20)
    }
}
