package dev.museframework.e2e.testkit

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

data class ProcessSpec(
    val jar: Path,
    val log: Path,
    val workingDirectory: Path,
    val jvmArgs: List<String> = emptyList(),
    val applicationArgs: List<String> = emptyList(),
    val startupTimeout: Duration = Duration.ofSeconds(30),
    val stopTimeout: Duration = Duration.ofSeconds(10),
    val pollInterval: Duration = Duration.ofMillis(100),
) {
    init {
        require(!startupTimeout.isZero && !startupTimeout.isNegative) { "startupTimeout must be positive" }
        require(!stopTimeout.isZero && !stopTimeout.isNegative) { "stopTimeout must be positive" }
        require(!pollInterval.isZero && !pollInterval.isNegative) { "pollInterval must be positive" }
    }
}

data class StopResult(
    val forced: Boolean,
    val exitCode: Int,
)

class PackagedApplication private constructor(
    private val process: Process,
    private val stopTimeout: Duration,
    val logPath: Path,
) : AutoCloseable {
    val pid: Long = process.pid()

    private var stopped: StopResult? = null

    fun isAlive(): Boolean = process.isAlive

    @Synchronized
    fun stop(): StopResult {
        stopped?.let { return it }

        var forced = false
        if (process.isAlive) {
            process.destroy()
            if (!waitFor(stopTimeout)) {
                forced = true
                process.destroyForcibly()
                check(waitFor(stopTimeout)) {
                    "Packaged application did not stop after forced termination: pid=$pid"
                }
            }
        }
        return StopResult(forced, process.exitValue()).also { stopped = it }
    }

    override fun close() {
        stop()
    }

    private fun waitFor(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        var interrupted = Thread.interrupted()
        try {
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val MAX_LOG_TAIL_CHARACTERS = 12_000

        fun start(
            spec: ProcessSpec,
            readiness: (PackagedApplication) -> Boolean,
        ): PackagedApplication {
            val jar = spec.jar.toAbsolutePath().normalize()
            val log = spec.log.toAbsolutePath().normalize()
            val workingDirectory = spec.workingDirectory.toAbsolutePath().normalize()
            require(Files.isRegularFile(jar)) { "Packaged application JAR does not exist: $jar" }
            require(Files.isDirectory(workingDirectory)) {
                "Packaged application working directory does not exist: $workingDirectory"
            }
            log.parent?.let(Files::createDirectories)

            val java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize()
            val command =
                buildList {
                    add(java.toString())
                    addAll(spec.jvmArgs)
                    add("-jar")
                    add(jar.toString())
                    addAll(spec.applicationArgs)
                }
            val process =
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start()
            val application = PackagedApplication(process, spec.stopTimeout, log)

            try {
                application.awaitReadiness(spec.startupTimeout, spec.pollInterval, readiness)
                return application
            } catch (failure: Throwable) {
                runCatching { application.stop() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun PackagedApplication.awaitReadiness(
            timeout: Duration,
            pollInterval: Duration,
            readiness: (PackagedApplication) -> Boolean,
        ) {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (System.nanoTime() < deadline) {
                checkAlive()
                if (readiness(this)) {
                    checkAlive()
                    return
                }
                try {
                    val millis = pollInterval.toMillis()
                    val nanos = (pollInterval.minusMillis(millis).toNanos()).toInt()
                    Thread.sleep(millis, nanos)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
            throw IllegalStateException(
                "Packaged application was not ready within $timeout.\n${logTail()}",
            )
        }

        private fun PackagedApplication.checkAlive() {
            if (!isAlive()) {
                throw IllegalStateException(
                    "Packaged application exited with code ${process.exitValue()}.\n${logTail()}",
                )
            }
        }

        private fun PackagedApplication.logTail(): String {
            if (!Files.isRegularFile(logPath)) return "Application log was not created: $logPath"
            return "Application log tail:\n${Files.readString(logPath).takeLast(MAX_LOG_TAIL_CHARACTERS)}"
        }
    }
}
