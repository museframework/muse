package dev.museframework.e2e.composition

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import java.util.UUID
object CompositionProbeTransport {
    const val TRANSPORT_DIR_PROPERTY = "composition.probe.transportDir"

    private const val FAILURES_DIR = "failures"
    private const val RELEASES_DIR = "releases"
    private const val COMMANDS_DIR = "commands"
    private const val APPLIED_DIR = "commands/applied"

    fun configuredRoot(): Path? =
        System.getProperty(TRANSPORT_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(Path::of)

    fun ensureLayout(root: Path) {
        Files.createDirectories(root.resolve(FAILURES_DIR))
        Files.createDirectories(root.resolve(RELEASES_DIR))
        Files.createDirectories(root.resolve(COMMANDS_DIR))
        Files.createDirectories(root.resolve(APPLIED_DIR))
    }

    fun publishFailure(
        root: Path,
        observation: FailureObservation,
    ) {
        publishProperties(
            root.resolve(FAILURES_DIR),
            "failure-${observation.attemptId}.properties",
            failureProperties(observation),
        )
    }

    fun publishRelease(
        root: Path,
        observation: ReleaseObservation,
    ) {
        publishProperties(
            root.resolve(RELEASES_DIR),
            "release-${observation.attemptId}.properties",
            releaseProperties(observation),
        )
    }

    fun publishResumeCommand(
        root: Path,
        attemptId: Long,
    ) {
        publishProperties(
            root.resolve(COMMANDS_DIR),
            "resume-$attemptId-${UUID.randomUUID()}.properties",
            propertiesOf(
                "command" to "resume",
                "attemptId" to attemptId.toString(),
            ),
        )
    }

    fun publishShutdownCommand(root: Path) {
        publishProperties(
            root.resolve(COMMANDS_DIR),
            "shutdown-${UUID.randomUUID()}.properties",
            propertiesOf("command" to "shutdown"),
        )
    }

    fun pollFailure(
        root: Path,
        id: String,
        namespace: String,
    ): FailureObservation? =
        Files.list(root.resolve(FAILURES_DIR)).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".properties") }
                .map { path -> readFailure(path) }
                .filter { it.id == id && it.namespace == namespace }
                .findFirst()
                .orElse(null)
        }

    fun pollRelease(
        root: Path,
        attemptId: Long,
    ): ReleaseObservation? {
        val path = root.resolve(RELEASES_DIR).resolve("release-$attemptId.properties")
        return if (Files.isRegularFile(path)) readRelease(path) else null
    }

    fun drainCommands(root: Path): List<ProbeCommand> {
        val commandsDir = root.resolve(COMMANDS_DIR)
        if (!Files.isDirectory(commandsDir)) return emptyList()
        return Files.list(commandsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".properties") }
                .sorted()
                .map { path ->
                    val properties = Files.newInputStream(path).use { Properties().apply { load(it) } }
                    val command =
                        when (properties.getProperty("command")) {
                            "resume" ->
                                ProbeCommand.Resume(
                                    attemptId = properties.getProperty("attemptId")?.toLongOrNull()
                                        ?: error("Missing attemptId in $path"),
                                )
                            "shutdown" -> ProbeCommand.Shutdown
                            else -> error("Unknown probe command in $path")
                        }
                    val applied =
                        root.resolve(APPLIED_DIR).resolve(path.fileName.toString())
                    Files.move(path, applied, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    command
                }.toList()
        }
    }

    fun awaitFailure(
        root: Path,
        id: String,
        namespace: String,
        timeout: Duration,
    ): FailureObservation {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            pollFailure(root, id, namespace)?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for failure observation id=$id namespace=$namespace")
    }

    fun awaitRelease(
        root: Path,
        attemptId: Long,
        timeout: Duration,
    ): ReleaseObservation {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            pollRelease(root, attemptId)?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for release observation attemptId=$attemptId")
    }

    private fun failureProperties(observation: FailureObservation): Properties =
        propertiesOf(
            "attemptId" to observation.attemptId.toString(),
            "id" to observation.id,
            "namespace" to observation.namespace,
            "identifier" to observation.identifier,
        )

    private fun releaseProperties(observation: ReleaseObservation): Properties =
        propertiesOf(
            "attemptId" to observation.attemptId.toString(),
            "providerClosed" to observation.providerClosed.toString(),
            "managed" to observation.managed.toString(),
        )

    private fun readFailure(path: Path): FailureObservation {
        val properties = Files.newInputStream(path).use { Properties().apply { load(it) } }
        return FailureObservation(
            attemptId = properties.getProperty("attemptId")?.toLongOrNull()
                ?: error("Missing attemptId in $path"),
            id = properties.getProperty("id") ?: error("Missing id in $path"),
            namespace = properties.getProperty("namespace") ?: error("Missing namespace in $path"),
            identifier = properties.getProperty("identifier") ?: error("Missing identifier in $path"),
        )
    }

    private fun readRelease(path: Path): ReleaseObservation {
        val properties = Files.newInputStream(path).use { Properties().apply { load(it) } }
        return ReleaseObservation(
            attemptId = properties.getProperty("attemptId")?.toLongOrNull()
                ?: error("Missing attemptId in $path"),
            providerClosed = properties.getProperty("providerClosed")?.toBooleanStrictOrNull()
                ?: error("Missing providerClosed in $path"),
            managed = properties.getProperty("managed")?.toBooleanStrictOrNull()
                ?: error("Missing managed in $path"),
        )
    }

    private fun publishProperties(
        directory: Path,
        fileName: String,
        properties: Properties,
    ) {
        Files.createDirectories(directory)
        val target = directory.resolve(fileName)
        val temporary = directory.resolve("$fileName.${UUID.randomUUID()}.tmp")
        Files.newOutputStream(temporary).use { output ->
            properties.store(output, "composition-probe")
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun propertiesOf(vararg entries: Pair<String, String>): Properties =
        Properties().apply {
            entries.forEach { (key, value) -> setProperty(key, value) }
        }
}

sealed interface ProbeCommand {
    data class Resume(val attemptId: Long) : ProbeCommand

    data object Shutdown : ProbeCommand
}

class CompositionProbeCommandWatcher(
    private val root: Path,
) : AutoCloseable {
    @Volatile
    private var running = true

    private val thread =
        Thread(
            {
                while (running) {
                    try {
                        CompositionProbeTransport.drainCommands(root).forEach { command ->
                            when (command) {
                                is ProbeCommand.Resume ->
                                    CompositionProbe.applyResumeCommand(command.attemptId)
                                ProbeCommand.Shutdown ->
                                    CompositionProbe.applyShutdownCommand()
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            },
            "composition-probe-command-watcher",
        ).apply {
            isDaemon = true
            start()
        }

    override fun close() {
        running = false
        thread.interrupt()
    }
}
