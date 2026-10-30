package dev.museframework.e2e.composition

import java.nio.file.Path
import java.time.Duration

class CompositionProbeClient(
    private val transportRoot: Path,
) : AutoCloseable {
    init {
        CompositionProbeTransport.ensureLayout(transportRoot)
    }

    fun awaitFailure(
        id: String,
        namespace: String,
        timeout: Duration,
    ): FailureObservation = CompositionProbeTransport.awaitFailure(transportRoot, id, namespace, timeout)

    fun awaitRelease(
        attemptId: Long,
        timeout: Duration,
    ): ReleaseObservation = CompositionProbeTransport.awaitRelease(transportRoot, attemptId, timeout)

    fun resumeRetries(attemptId: Long) {
        CompositionProbeTransport.publishResumeCommand(transportRoot, attemptId)
    }

    fun signalShutdown() {
        CompositionProbeTransport.publishShutdownCommand(transportRoot)
    }

    override fun close() = Unit
}
