package dev.museframework.e2e.sftp

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedSftpOccupiedPortE2ETest {
    private val temporaryDirectory: Path = Files.createTempDirectory("muse-sftp-packaged-occupied-")
    private val port = requiredProperty("packaged.e2e.port").toInt()
    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val baseApplicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()

    @Test
    fun `occupied HTTP port fails startup with evidence and no orphan process`() {
        try {
            ServerSocket(port).use { held ->
                held.reuseAddress = true
                val failureLog = baseApplicationLog.resolveSibling("sftp-occupied-http.log")
                val failure =
                    runCatching {
                        PackagedApplication.start(
                            ProcessSpec(
                                jar = applicationJar,
                                log = failureLog,
                                workingDirectory = temporaryDirectory,
                                applicationArgs =
                                    listOf(
                                        "--server.port=$port",
                                        "--spring.main.banner-mode=off",
                                    ),
                                startupTimeout = Duration.ofSeconds(15),
                                stopTimeout = Duration.ofSeconds(10),
                                pollInterval = Duration.ofMillis(100),
                            ),
                            readiness = { false },
                        )
                    }.exceptionOrNull()

                assertTrue(failure is IllegalStateException, "Occupied HTTP port must fail packaged startup")
                val evidence =
                    buildString {
                        appendLine((failure as IllegalStateException).message.orEmpty())
                        if (Files.isRegularFile(failureLog)) append(Files.readString(failureLog))
                    }
                assertTrue(
                    evidence.contains("Address already in use", ignoreCase = true) ||
                        evidence.contains("BindException", ignoreCase = true) ||
                        evidence.contains("Port $port was already in use", ignoreCase = true) ||
                        evidence.contains("port $port was already in use", ignoreCase = true),
                    "Failure evidence must diagnose occupied HTTP bind for port $port",
                )
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }
}
