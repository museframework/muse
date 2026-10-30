package dev.museframework.e2e.file

import dev.museframework.e2e.testkit.PackagedApplication
import dev.museframework.e2e.testkit.ProcessFileProbe
import dev.museframework.e2e.testkit.ProcessSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackagedFileConnectorE2ETest {
    private val processWorkingDirectory: Path = Files.createTempDirectory("muse-file-packaged-e2e-")
    private val handwrittenRoot: Path = processWorkingDirectory.resolve("handwritten")
    private val generatedRoot: Path = processWorkingDirectory.resolve("generated")

    private val applicationJar = Path.of(requiredProperty("packaged.e2e.jar")).toAbsolutePath()
    private val baseApplicationLog = Path.of(requiredProperty("packaged.e2e.log")).toAbsolutePath()
    private val applicationLog: Path
        get() = baseApplicationLog.resolveSibling("file-connector.log")

    private lateinit var packagedApplication: PackagedApplication

    @BeforeAll
    fun startPackagedApplication() {
        prepareIncomingDirectories()
        publishSentinelJourneys()

        packagedApplication =
            PackagedApplication.start(
                ProcessSpec(
                    jar = applicationJar,
                    log = applicationLog,
                    workingDirectory = processWorkingDirectory,
                    jvmArgs =
                        listOf(
                            "-Dpackaged.file.directory=${handwrittenRoot.toAbsolutePath()}",
                        ),
                    applicationArgs = listOf("--spring.main.banner-mode=off"),
                    startupTimeout = STARTUP_TIMEOUT,
                    stopTimeout = PROCESS_STOP_TIMEOUT,
                    pollInterval = READINESS_POLL_INTERVAL,
                ),
                readiness = { application ->
                    operationalReadiness(
                        application,
                        processRoot = processWorkingDirectory,
                        handwrittenRootOverride = handwrittenRoot,
                        generatedRootOverride = generatedRoot,
                    )
                },
            )

        check(packagedApplication.pid != ProcessHandle.current().pid()) {
            "Packaged application must run in a separate JVM"
        }

        purgeSentinelArtifacts()
    }

    @AfterAll
    fun stopPackagedApplication() {
        try {
            if (::packagedApplication.isInitialized) {
                val stopResult = packagedApplication.stop()
                assertFalse(stopResult.forced, "Packaged file application stop must be graceful")
            }
        } finally {
            processWorkingDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packaged file connector streams and archives a polled file`() {
        val input = handwrittenRoot.resolve("incoming/input.txt")
        val output = handwrittenRoot.resolve("output/copied.txt")
        val archived = handwrittenRoot.resolve("archive/processed.txt")
        publishAtomically(input, PACKAGED_FILE_CONTENT)

        awaitFileState(output, input, archived)

        assertEquals(PACKAGED_FILE_CONTENT, Files.readString(output, StandardCharsets.UTF_8))
        assertEquals(PACKAGED_FILE_CONTENT, Files.readString(archived, StandardCharsets.UTF_8))
    }

    @Test
    fun `generated file listener streams and archives a polled file`() {
        val input = generatedRoot.resolve("incoming/input.txt")
        val output = generatedRoot.resolve("output/copied.txt")
        val archived = generatedRoot.resolve("archive/processed.txt")
        publishAtomically(input, GENERATED_FILE_CONTENT)

        awaitFileState(output, input, archived)

        assertEquals(GENERATED_FILE_CONTENT, Files.readString(output, StandardCharsets.UTF_8))
        assertEquals(GENERATED_FILE_CONTENT, Files.readString(archived, StandardCharsets.UTF_8))
    }

    @EnabledOnOs(OS.LINUX, OS.MAC)
    @Test
    fun `file listener releases completed input handles before graceful shutdown`() {
        val input = handwrittenRoot.resolve("incoming/handle-release.txt")
        val output = handwrittenRoot.resolve("output/copied.txt")
        val archived = handwrittenRoot.resolve("archive/processed.txt")
        publishAtomically(input, "handle-release-content")

        awaitFileState(output, input, archived)

        val openPaths = ProcessFileProbe.openPaths(packagedApplication.pid)
        val handwrittenRootReal = handwrittenRoot.toRealPath()
        assertTrue(
            openPaths.none { it.startsWith(handwrittenRootReal) },
            "Completed input must not keep descriptors under $handwrittenRootReal; open=$openPaths",
        )

        val followUp = handwrittenRoot.resolve("incoming/follow-up.txt")
        publishAtomically(followUp, "follow-up-content")
        awaitFileState(output, followUp, archived)
        assertEquals("follow-up-content", Files.readString(output, StandardCharsets.UTF_8))
    }

    @Test
    fun `readiness rejects a context-ready child when handwritten incoming is unavailable`() {
        val scratchDirectory = Files.createTempDirectory("muse-file-packaged-readiness-negative-")
        val scratchHandwrittenRoot = scratchDirectory.resolve("handwritten")
        val scratchGeneratedRoot = scratchDirectory.resolve("generated")
        val failureLog = baseApplicationLog.resolveSibling("readiness-negative.log")
        try {
            Files.createDirectories(scratchHandwrittenRoot)
            Files.createDirectories(scratchGeneratedRoot.resolve("incoming"))
            prepareCsvDirectories(scratchDirectory)
            assertFalse(
                Files.isDirectory(scratchHandwrittenRoot.resolve("incoming")),
                "Handwritten incoming must be absent so the listener source fails",
            )
            publishAtomically(
                scratchGeneratedRoot.resolve("incoming/$SENTINEL_INPUT_NAME"),
                GENERATED_SENTINEL_CONTENT,
            )

            val failure =
                runCatching {
                    PackagedApplication.start(
                        ProcessSpec(
                            jar = applicationJar,
                            log = failureLog,
                            workingDirectory = scratchDirectory,
                            jvmArgs =
                                listOf(
                                    "-Dpackaged.file.directory=${scratchHandwrittenRoot.toAbsolutePath()}",
                                ),
                            applicationArgs = listOf("--spring.main.banner-mode=off"),
                            startupTimeout = Duration.ofSeconds(8),
                            stopTimeout = PROCESS_STOP_TIMEOUT,
                            pollInterval = READINESS_POLL_INTERVAL,
                        ),
                        readiness = { application ->
                            operationalReadiness(
                                application,
                                processRoot = scratchDirectory,
                                handwrittenRootOverride = scratchHandwrittenRoot,
                                generatedRootOverride = scratchGeneratedRoot,
                            )
                        },
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException, "Expected readiness timeout, got=$failure")
            assertTrue(
                failure.message.orEmpty().contains("was not ready within"),
                failure.message,
            )
            assertTrue(Files.isRegularFile(failureLog), "Failure log must exist: $failureLog")
            val logText = Files.readString(failureLog)
            assertTrue(
                logText.contains("FileConnectorException"),
                "Failure log must retain handwritten source failure diagnostics",
            )
            assertTrue(
                logText.contains("incoming"),
                "Failure log must reference the missing incoming directory",
            )
            assertTrue(
                Files.isRegularFile(scratchDirectory.resolve(FixtureReadyMarker.CONTEXT_READY_FILE)),
                "Context marker may appear before operational readiness is satisfied",
            )
            val childPid = extractChildPid(logText)
            assertFalse(
                ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "Readiness timeout must terminate the child process pid=$childPid",
            )
        } finally {
            scratchDirectory.toFile().deleteRecursively()
        }
    }

    private fun operationalReadiness(
        application: PackagedApplication,
        processRoot: Path,
        handwrittenRootOverride: Path,
        generatedRootOverride: Path,
    ): Boolean {
        if (!application.isAlive()) return false
        if (application.pid == ProcessHandle.current().pid()) return false
        if (!Files.isRegularFile(processRoot.resolve(FixtureReadyMarker.CONTEXT_READY_FILE))) {
            return false
        }
        if (!journeyComplete(handwrittenRootOverride, HANDWRITTEN_SENTINEL_CONTENT)) {
            return false
        }
        if (!journeyComplete(generatedRootOverride, GENERATED_SENTINEL_CONTENT)) {
            return false
        }
        return true
    }

    private fun journeyComplete(
        root: Path,
        expectedContent: String,
    ): Boolean {
        val input = root.resolve("incoming/$SENTINEL_INPUT_NAME")
        val output = root.resolve("output/copied.txt")
        val archived = root.resolve("archive/processed.txt")
        return Files.isRegularFile(output) &&
            Files.isRegularFile(archived) &&
            !Files.exists(input) &&
            Files.readString(output, StandardCharsets.UTF_8) == expectedContent &&
            Files.readString(archived, StandardCharsets.UTF_8) == expectedContent
    }

    private fun prepareIncomingDirectories() {
        Files.createDirectories(handwrittenRoot.resolve("incoming"))
        Files.createDirectories(generatedRoot.resolve("incoming"))
        prepareCsvDirectories(processWorkingDirectory)
    }

    private fun prepareCsvDirectories(root: Path) {
        listOf("large", "progressive", "malformed", "body-failure").forEach {
            Files.createDirectories(root.resolve("csv/commands/$it"))
        }
    }

    private fun publishSentinelJourneys() {
        publishAtomically(
            handwrittenRoot.resolve("incoming/$SENTINEL_INPUT_NAME"),
            HANDWRITTEN_SENTINEL_CONTENT,
        )
        publishAtomically(
            generatedRoot.resolve("incoming/$SENTINEL_INPUT_NAME"),
            GENERATED_SENTINEL_CONTENT,
        )
    }

    private fun purgeSentinelArtifacts() {
        listOf(handwrittenRoot, generatedRoot).forEach { root ->
            Files.deleteIfExists(root.resolve("incoming/$SENTINEL_INPUT_NAME"))
            Files.deleteIfExists(root.resolve("output/copied.txt"))
            Files.deleteIfExists(root.resolve("archive/processed.txt"))
        }
    }

    private fun publishAtomically(
        target: Path,
        content: String,
    ) {
        Files.createDirectories(target.parent)
        val staging =
            if (target.parent.fileName.toString() == "incoming") {
                val flowRoot = target.parent.parent
                Files.createDirectories(flowRoot.resolve(".atomic-publish"))
                flowRoot.resolve(".atomic-publish/${target.fileName}.part")
            } else {
                target.parent.resolve(".${target.fileName}.staging")
            }
        Files.writeString(staging, content, StandardCharsets.UTF_8)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun extractChildPid(logText: String): Long {
        Regex("""with PID (\d+)""", RegexOption.IGNORE_CASE)
            .find(logText)
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?.let { return it }

        Regex("""process running with pid (\d+)""", RegexOption.IGNORE_CASE)
            .find(logText)
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?.let { return it }

        return Regex("""\bpid=(\d+)\b""")
            .findAll(logText)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?: error("Could not extract child pid from application log")
    }

    private fun awaitFileState(
        output: Path,
        input: Path,
        archived: Path,
    ) {
        val deadline = System.nanoTime() + FILE_OPERATION_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(output) && !Files.exists(input) && Files.isRegularFile(archived)) return
            if (::packagedApplication.isInitialized && !packagedApplication.isAlive()) {
                error("Packaged application exited before file state settled.\n${applicationLogTail()}")
            }
            Thread.sleep(FILE_STATE_RETRY_MILLIS)
        }
        error(
            "File connector did not produce output and archive input within $FILE_OPERATION_TIMEOUT.\n" +
                applicationLogTail(),
        )
    }

    private fun applicationLogTail(): String {
        if (!Files.isRegularFile(applicationLog)) {
            return "Application log was not created: $applicationLog"
        }
        val text = Files.readString(applicationLog)
        return "Application log tail:\n${text.takeLast(MAX_LOG_TAIL_CHARACTERS)}"
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Required system property is missing: $name" }

    private companion object {
        const val PACKAGED_FILE_CONTENT = "packaged-file-content"
        const val GENERATED_FILE_CONTENT = "packaged-generated-file-content"
        const val HANDWRITTEN_SENTINEL_CONTENT = "packaged-file-sentinel-handwritten"
        const val GENERATED_SENTINEL_CONTENT = "packaged-file-sentinel-generated"
        const val SENTINEL_INPUT_NAME = "readiness-sentinel.txt"

        val STARTUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val FILE_OPERATION_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val FILE_STATE_RETRY_MILLIS = 25L
        val PROCESS_STOP_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READINESS_POLL_INTERVAL: Duration = Duration.ofMillis(100)
        const val MAX_LOG_TAIL_CHARACTERS = 12_000
    }
}
