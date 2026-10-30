package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals

class CompositionProbeTransportTest {
    @Test
    fun `transport publishes and polls failure and release evidence`() {
        val root = Files.createTempDirectory("composition-probe-transport-")
        CompositionProbeTransport.ensureLayout(root)
        val failure =
            FailureObservation(
                attemptId = 3L,
                id = "db-failure.bin",
                namespace = "DB",
                identifier = "QUERY_EXECUTION",
            )
        CompositionProbeTransport.publishFailure(root, failure)
        assertEquals(
            failure,
            CompositionProbeTransport.awaitFailure(root, "db-failure.bin", "DB", Duration.ofSeconds(1)),
        )

        val release = ReleaseObservation(attemptId = 3L, providerClosed = true, managed = false)
        CompositionProbeTransport.publishRelease(root, release)
        assertEquals(
            release,
            CompositionProbeTransport.awaitRelease(root, 3L, Duration.ofSeconds(1)),
        )
    }
}
