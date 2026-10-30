package dev.museframework.e2e.testkit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

object ProcessFileProbe {
    private val inspectionTimeout: Duration = Duration.ofSeconds(5)

    fun openPaths(pid: Long): Set<Path> {
        check(ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent) {
            "Cannot inspect files for non-live process $pid"
        }
        return when {
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) ->
                readLinuxPaths(Path.of("/proc", pid.toString(), "fd"))
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
                parseLsof(runInspection(listOf("lsof", "-a", "-p", pid.toString(), "-Fn"), inspectionTimeout))
            else -> error("Process file inspection is unsupported on ${System.getProperty("os.name")}")
        }
    }

    internal fun readLinuxPaths(
        descriptorDirectory: Path,
        readLink: (Path) -> Path = Files::readSymbolicLink,
    ): Set<Path> {
        check(Files.isDirectory(descriptorDirectory)) {
            "Linux process descriptor directory is unavailable: $descriptorDirectory"
        }
        val paths = linkedSetOf<Path>()
        try {
            Files.list(descriptorDirectory).use { descriptors ->
                descriptors.forEach { descriptor ->
                    val target =
                        try {
                            readLink(descriptor).toString().removeSuffix(" (deleted)")
                        } catch (_: NoSuchFileException) {
                            return@forEach
                        }
                    if (isPseudoDescriptor(target)) return@forEach
                    val path =
                        try {
                            Path.of(target)
                        } catch (failure: RuntimeException) {
                            throw IllegalStateException(
                                "Malformed Linux descriptor target for $descriptor: $target",
                                failure,
                            )
                        }
                    check(path.isAbsolute) {
                        "Malformed relative Linux descriptor target for $descriptor: $target"
                    }
                    paths.add(path.normalize())
                }
            }
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Could not inspect Linux process descriptors in $descriptorDirectory",
                failure,
            )
        }
        return paths
    }

    internal fun parseLsof(lines: List<String>): Set<Path> {
        val paths = linkedSetOf<Path>()
        lines.forEach { line ->
            when {
                line.startsWith("n") -> {
                    val target = line.substring(1).removeSuffix(" (deleted)")
                    check(target.isNotBlank()) { "Malformed lsof name field: '$line'" }
                    if (target.startsWith("/")) {
                        val path =
                            try {
                                Path.of(target)
                            } catch (failure: RuntimeException) {
                                throw IllegalStateException("Malformed lsof path: $target", failure)
                            }
                        paths.add(path.toAbsolutePath().normalize())
                    }
                }
                line.startsWith("p") || line.startsWith("f") || line.isBlank() -> Unit
                else -> error("Malformed lsof field: '$line'")
            }
        }
        return paths
    }

    internal fun runInspection(
        command: List<String>,
        timeout: Duration,
    ): List<String> {
        // Redirect to a file so a chatty observer (e.g. large macOS lsof -Fn output)
        // cannot fill the OS pipe and deadlock waitFor() before we drain stdout.
        val outputFile = Files.createTempFile("muse-process-file-probe-", ".out")
        try {
            val process =
                try {
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start()
                } catch (failure: IOException) {
                    throw IllegalStateException(
                        "Could not start process inspection tool '${command.firstOrNull()}'",
                        failure,
                    )
                }

            val completed =
                try {
                    process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)
                } catch (interrupted: InterruptedException) {
                    terminate(process)
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            if (!completed) {
                terminate(process)
                error("Process inspection command timed out after $timeout: ${command.joinToString(" ")}")
            }

            val output = Files.readAllLines(outputFile)
            check(process.exitValue() == 0) {
                "Process inspection command failed with code ${process.exitValue()}: ${output.joinToString("\n")}"
            }
            return output
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!waitForPreservingInterrupt(process, Duration.ofSeconds(1))) {
            process.destroyForcibly()
            check(waitForPreservingInterrupt(process, Duration.ofSeconds(1))) {
                "Process inspection subprocess did not terminate: pid=${process.pid()}"
            }
        }
    }

    private fun waitForPreservingInterrupt(
        process: Process,
        timeout: Duration,
    ): Boolean {
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

    private fun isPseudoDescriptor(target: String): Boolean =
        target.startsWith("socket:[") ||
            target.startsWith("pipe:[") ||
            target.startsWith("anon_inode:") ||
            target.startsWith("memfd:")
}
