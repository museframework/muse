package dev.museframework.e2e.composition

import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.api.spi.ProcessorBuilder
import dev.museframework.engine.api.streaming.MuseCursorProvider
import dev.museframework.file.api.model.LocalFileAttributes
import kotlinx.coroutines.CompletableDeferred
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

data class FailureObservation(
    val attemptId: Long,
    val id: String,
    val namespace: String,
    val identifier: String,
)

data class ReleaseObservation(
    val attemptId: Long,
    val providerClosed: Boolean,
    val managed: Boolean,
)

object CompositionProbe {
    private const val MAX_OBSERVER_THREADS = 4
    private val admissionPermits = Semaphore(1)
    private val failureGateOpen = AtomicBoolean(true)
    private val shutdownRequested = AtomicBoolean(false)
    private val attemptSequence = AtomicLong(0L)
    private val closedFailureGeneration = AtomicReference<Long?>(null)
    private val gateMonitor = Any()

    @Volatile
    private var observerExecutor: ExecutorService = newObserverExecutor()

    private val commandWatcher: CompositionProbeCommandWatcher? =
        CompositionProbeTransport.configuredRoot()?.let { root ->
            CompositionProbeTransport.ensureLayout(root)
            CompositionProbeCommandWatcher(root)
        }

    private val transportRoot: java.nio.file.Path? = CompositionProbeTransport.configuredRoot()

    private val failureObservations = ConcurrentHashMap<Long, FailureObservation>()
    private val releaseObservations = ConcurrentHashMap<Long, ReleaseObservation>()
    private val parkedWaiters = ConcurrentHashMap<Long, CopyOnWriteArrayList<CompletableDeferred<Unit>>>()
    private val attemptProviders = ConcurrentHashMap<Long, MuseCursorProvider<*>>()

    @Volatile
    private var activeAttemptId: Long? = null

    @Volatile
    internal var enrollmentPausedForTests: CountDownLatch? = null

    @Volatile
    internal var enrollmentContinueForTests: CountDownLatch? = null

    @Volatile
    internal var providerPublicationPausedForTests: CountDownLatch? = null

    @Volatile
    internal var providerPublicationContinueForTests: CountDownLatch? = null

    internal fun retainedProviderAssociationCountForTests(): Int = attemptProviders.size

    internal fun activeAttemptIdForTests(): Long? = activeAttemptId

    private fun newObserverExecutor(): ExecutorService =
        Executors.newFixedThreadPool(MAX_OBSERVER_THREADS) { runnable ->
            Thread(runnable, "composition-probe-observer").apply { isDaemon = true }
        }

    fun resetForTests() {
        synchronized(gateMonitor) {
            shutdownRequested.set(false)
            failureGateOpen.set(true)
            closedFailureGeneration.set(null)
            failureObservations.clear()
            releaseObservations.clear()
            parkedWaiters.values.forEach { waiters ->
                waiters.forEach { it.cancel(CancellationException("probe reset")) }
            }
            parkedWaiters.clear()
            attemptProviders.clear()
            activeAttemptId = null
            enrollmentPausedForTests = null
            enrollmentContinueForTests = null
            providerPublicationPausedForTests = null
            providerPublicationContinueForTests = null
        }
        while (admissionPermits.availablePermits() < 1) {
            admissionPermits.release()
        }
        if (observerExecutor.isShutdown) {
            observerExecutor = newObserverExecutor()
        }
    }

    fun signalShutdown() {
        applyShutdownCommand()
    }

    internal fun applyShutdownCommand() {
        synchronized(gateMonitor) {
            shutdownRequested.set(true)
            failureGateOpen.set(false)
            cancelParkedWaitersLocked(CancellationException("probe shutdown"))
            clearProviderAssociationsLocked()
            activeAttemptId = null
        }
        observerExecutor.shutdownNow()
    }

    internal fun applyResumeCommand(attemptId: Long) {
        if (shutdownRequested.get()) return
        resumeRetries(attemptId)
    }

    suspend fun admit(event: MuseEvent) {
        if (shutdownRequested.get()) {
            throw CancellationException("Composition probe is shut down")
        }
        admissionPermits.acquire()
        try {
            awaitOpenGateOrPark()
            val attemptId =
                synchronized(gateMonitor) {
                    if (shutdownRequested.get()) {
                        throw CancellationException("Composition probe is shut down")
                    }
                    val previousAttemptId = activeAttemptId
                    val nextAttemptId = attemptSequence.incrementAndGet()
                    discardCompletedSuccessProviderLocked(previousAttemptId, nextAttemptId)
                    nextAttemptId
                }
            providerPublicationPausedForTests?.countDown()
            providerPublicationContinueForTests?.await()
            synchronized(gateMonitor) {
                if (shutdownRequested.get()) {
                    throw CancellationException("Composition probe is shut down")
                }
                val provider = event.payload?.asCursorStreamProvider()
                if (provider != null) {
                    attemptProviders[attemptId] = provider
                }
                activeAttemptId = attemptId
            }
        } finally {
            admissionPermits.release()
        }
    }

    fun completeSuccessfulAttempt() {
        synchronized(gateMonitor) {
            val attemptId = activeAttemptId ?: return
            if (closedFailureGeneration.get() == attemptId) {
                return
            }
            attemptProviders.remove(attemptId)
            activeAttemptId = null
        }
    }

    fun recordFailure(event: MuseEvent): FailureObservation {
        val attemptId =
            activeAttemptId
                ?: error("Cannot record failure without an active admission attempt")
        synchronized(gateMonitor) {
            failureGateOpen.set(false)
            if (closedFailureGeneration.get() == null) {
                closedFailureGeneration.set(attemptId)
            }
        }

        val museError =
            requireNotNull(event.error) { "Failure probe requires event.error" }
        val observation =
            FailureObservation(
                attemptId = attemptId,
                id = sourceInputId(event),
                namespace = museError.errorType.namespace,
                identifier = museError.errorType.identifier,
            )
        failureObservations[attemptId] = observation
        transportRoot?.let { CompositionProbeTransport.publishFailure(it, observation) }

        val admittedProvider = attemptProviders[attemptId]
        if (admittedProvider != null) {
            scheduleReleaseObserver(attemptId, admittedProvider)
        }
        return observation
    }

    fun awaitFailure(
        id: String,
        namespace: String,
        timeout: Duration,
    ): FailureObservation {
        transportRoot?.let {
            return CompositionProbeTransport.awaitFailure(it, id, namespace, timeout)
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            failureObservations.values
                .firstOrNull { it.id == id && it.namespace == namespace }
                ?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for failure observation id=$id namespace=$namespace")
    }

    fun awaitRelease(
        attemptId: Long,
        timeout: Duration,
    ): ReleaseObservation {
        transportRoot?.let {
            return CompositionProbeTransport.awaitRelease(it, attemptId, timeout)
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            releaseObservations[attemptId]?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out waiting for release observation attemptId=$attemptId")
    }

    fun resumeRetries(attemptId: Long) {
        synchronized(gateMonitor) {
            if (shutdownRequested.get()) return
            val generation = closedFailureGeneration.get() ?: return
            if (generation != attemptId) return
            closedFailureGeneration.set(null)
            failureGateOpen.set(true)
            completeParkedWaitersLocked(generation)
        }
    }

    private suspend fun awaitOpenGateOrPark() {
        while (true) {
            synchronized(gateMonitor) {
                if (shutdownRequested.get()) {
                    throw CancellationException("Composition probe is shut down")
                }
                if (failureGateOpen.get()) {
                    return
                }
            }

            enrollmentPausedForTests?.countDown()
            enrollmentContinueForTests?.await()

            val waiterToAwait =
                synchronized(gateMonitor) {
                    if (shutdownRequested.get()) {
                        throw CancellationException("Composition probe is shut down")
                    }
                    if (failureGateOpen.get()) {
                        return
                    }
                    val generation =
                        closedFailureGeneration.get()
                            ?: error("Failure gate is closed without an associated generation")
                    val deferred = CompletableDeferred<Unit>()
                    parkedWaiters.computeIfAbsent(generation) { CopyOnWriteArrayList() }.add(deferred)
                    if (shutdownRequested.get()) {
                        parkedWaiters[generation]?.remove(deferred)
                        throw CancellationException("Composition probe is shut down")
                    }
                    if (failureGateOpen.get()) {
                        parkedWaiters[generation]?.remove(deferred)
                        deferred.complete(Unit)
                        return
                    }
                    deferred
                }
            try {
                waiterToAwait.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun completeParkedWaitersLocked(generation: Long) {
        parkedWaiters.remove(generation)?.forEach { it.complete(Unit) }
    }

    private fun cancelParkedWaitersLocked(reason: CancellationException) {
        parkedWaiters.values.forEach { waiters ->
            waiters.forEach { it.cancel(reason) }
        }
        parkedWaiters.clear()
    }

    private fun discardCompletedSuccessProviderLocked(
        previousAttemptId: Long?,
        nextAttemptId: Long,
    ) {
        if (previousAttemptId == null || previousAttemptId == nextAttemptId) return
        if (closedFailureGeneration.get() == previousAttemptId) return
        attemptProviders.remove(previousAttemptId)
    }

    private fun clearProviderAssociationsLocked() {
        attemptProviders.clear()
    }

    private fun scheduleReleaseObserver(
        attemptId: Long,
        provider: MuseCursorProvider<*>,
    ) {
        observerExecutor.execute {
            try {
                val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                while (System.nanoTime() < deadline && !shutdownRequested.get()) {
                    if (provider.isClosed && !provider.isManaged) {
                        publishRelease(attemptId, providerClosed = true, managed = false)
                        return@execute
                    }
                    Thread.sleep(5)
                }
            } finally {
                synchronized(gateMonitor) {
                    attemptProviders.remove(attemptId)
                }
            }
        }
    }

    private fun publishRelease(
        attemptId: Long,
        providerClosed: Boolean,
        managed: Boolean,
    ) {
        val observation =
            ReleaseObservation(
                attemptId = attemptId,
                providerClosed = providerClosed,
                managed = managed,
            )
        releaseObservations[attemptId] = observation
        transportRoot?.let { CompositionProbeTransport.publishRelease(it, observation) }
    }

    private fun sourceInputId(event: MuseEvent): String {
        val attributes = event.attributes?.value
        if (attributes is LocalFileAttributes) {
            return attributes.fileName
        }
        error("Failure probe requires LocalFileAttributes with fileName")
    }
}

class CompositionProbeMuseProcessor(
    override val component: CompositionProbeConfig,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult =
        when (component.phase) {
            ProbePhase.ADMISSION -> {
                CompositionProbe.admit(context.event)
                ProcessorResult.Success(context)
            }
            ProbePhase.SUCCESS -> {
                CompositionProbe.completeSuccessfulAttempt()
                ProcessorResult.Success(context)
            }
            ProbePhase.FAILURE -> {
                CompositionProbe.recordFailure(context.event)
                ProcessorResult.Success(context)
            }
        }
}

val compositionProbeProcessorBuilder =
    ProcessorBuilder<CompositionProbeConfig> { component, _ ->
        CompositionProbeMuseProcessor(component)
    }
