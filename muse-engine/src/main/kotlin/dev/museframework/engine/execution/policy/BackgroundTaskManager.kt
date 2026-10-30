package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ResolvedErrorHandler
import dev.museframework.engine.api.execution.policy.BackgroundAdmission
import dev.museframework.engine.api.execution.policy.BackgroundExecutionConfig
import dev.museframework.engine.api.execution.policy.BackgroundHandle
import dev.museframework.engine.api.execution.policy.BackgroundRejection
import dev.museframework.engine.api.execution.policy.BackgroundTask
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.streaming.BackgroundStreamPreparation
import dev.museframework.engine.api.streaming.StreamingEventResources
import dev.museframework.engine.api.streaming.StreamingException
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An engine-issued background declaration, bound to exactly one prepared policy occurrence. */
internal class BoundBackgroundHandle(
    val application: PolicyBindings,
    val binding: Any,
    val maxConcurrency: Int?,
    val timeoutMillis: Long?,
) : BackgroundHandle

/**
 * Application-owned supervisor and ledger for accepted background work.
 *
 * Capacity covers queued and running tasks until their descendants and provider cleanup finish.
 * Work is started only under [supervisor], never in the submitting request's coroutine scope, so
 * request completion or cancellation cannot cancel an accepted task.
 */
internal class BackgroundTaskManager(
    private val config: BackgroundExecutionConfig,
    dispatcher: CoroutineDispatcher,
) {
    init {
        val defaultTimeout = config.defaultTimeoutMillis
        require(config.maxOutstandingTasks > 0) {
            "Background maxOutstandingTasks must be positive, was ${config.maxOutstandingTasks}"
        }
        require(config.shutdownGraceMillis >= 0) {
            "Background shutdownGraceMillis must be nonnegative, was ${config.shutdownGraceMillis}"
        }
        require(defaultTimeout == null || defaultTimeout > 0) {
            "Background defaultTimeoutMillis must be positive, was $defaultTimeout"
        }
    }

    private val logger = LoggerFactory.getLogger(BackgroundTaskManager::class.java)
    private val supervisor = CoroutineScope(dispatcher + SupervisorJob())
    private val admissionLock = Any()
    private val semaphores = IdentityHashMap<Any, Semaphore>()
    private val ledger = LinkedHashSet<BackgroundRecord>()
    private var accepting = true
    private var outstanding = 0

    /**
     * Reserves capacity, then claims the event's resource graph from the submitting owner,
     * prepares transport-bound providers and publishes the task atomically. Every step after the
     * reservation sits under one rollback guard that is disarmed only at publication.
     */
    suspend fun submit(
        handle: BoundBackgroundHandle,
        body: BoundBody,
        event: MuseEvent,
        context: ExecutionContext,
    ): BackgroundAdmission {
        val caller = currentCoroutineContext()[Job]
        when (reserve()) {
            Reservation.Closed -> return BackgroundAdmission.Rejected(BackgroundRejection.STOPPING)
            Reservation.Full -> return BackgroundAdmission.Rejected(BackgroundRejection.CAPACITY)
            Reservation.Granted -> Unit
        }

        val registry = StreamingResourceRegistry()
        var published = false
        try {
            claimEventResources(event, context, registry)
            val record =
                BackgroundRecord(
                    application = handle.application,
                    binding = handle.binding,
                    semaphore = permitFor(handle.binding, handle.maxConcurrency),
                    timeoutMillis = handle.timeoutMillis ?: config.defaultTimeoutMillis,
                    body = body,
                    event = event,
                    context = context,
                    registry = registry,
                )
            published = publish(record, caller)
            if (!published) {
                caller?.ensureActive()
                return BackgroundAdmission.Rejected(BackgroundRejection.STOPPING)
            }
            start(record)
            return BackgroundAdmission.Accepted(BackgroundTaskHandle(record.job, record.completed))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Expected inability to retain the input is a caller-owned rejection; anything else is
            // a contract defect and is never turned into an accepted task.
            if (!published && (failure is IllegalStateException || failure is StreamingException)) {
                return BackgroundAdmission.Rejected(BackgroundRejection.INPUT_NOT_RETAINABLE)
            }
            throw failure
        } finally {
            // Disarmed exactly at publication, where the accepted record owns claims and capacity.
            if (!published) rollback(registry)
        }
    }

    /**
     * Claims the event's provider graph for the branch.
     *
     * Acquisition is checked against the submitting registry: every identity must already be owned
     * there, and a closed or already-cleaned identity is refused instead of being invented. The
     * temporary retained claim is held while the branch registry adopts each identity, which closes
     * the count-zero cleanup window, and is released only after the branch claims exist; the branch
     * registry is then the only long-lived owner.
     */
    private suspend fun claimEventResources(
        event: MuseEvent,
        context: ExecutionContext,
        registry: StreamingResourceRegistry,
    ) {
        val providers = StreamingEventResources.eventProviders(event)
        if (providers.isNotEmpty()) {
            val lease = context.streamingResources.retain(providers)
            try {
                providers.forEach(registry::register)
            } finally {
                lease.close()
            }
        }
        // Provider-supplied preparation runs outside every engine lock, and only for the
        // providers this event actually carries.
        providers.forEach { provider ->
            if (provider is BackgroundStreamPreparation) provider.prepareForBackgroundUse()
        }
    }

    /** Returns true when the accepted record owns its claims and its reservation. */
    private fun publish(record: BackgroundRecord, caller: Job?): Boolean =
        synchronized(admissionLock) {
            if (accepting && caller?.isCancelled != true) {
                // Lazily created: publication owns the job before any body code can run.
                record.job = supervisor.launch(start = CoroutineStart.LAZY) { supervise(record) }
                ledger += record
                true
            } else {
                false
            }
        }

    /** Starts the published job; a scheduling failure still terminally finalizes the record. */
    private fun start(record: BackgroundRecord) {
        try {
            record.job.invokeOnCompletion { finalize(record) }
            record.job.start()
        } catch (failure: Throwable) {
            try {
                report("Background task {} could not be scheduled", record.taskId, failure)
            } finally {
                finalize(record)
            }
        }
    }

    /** Closes admission; accepted work keeps running and keeps its bindings until it finalizes. */
    fun closeAdmission() {
        synchronized(admissionLock) { accepting = false }
    }

    /** The configured grace period before unfinished accepted work is cancelled. */
    val graceMillis: Long
        get() = config.shutdownGraceMillis

    /** Joins finalization of every accepted task; no historical event graph is retained. */
    suspend fun awaitSettled() {
        accepted().map { it.completed }.joinAll()
    }

    /** Cancels accepted work that is still unfinished. */
    fun cancelOutstanding() {
        accepted().forEach { it.job.cancel() }
    }

    /**
     * Cancels unfinished accepted work and joins its finalization without waiting another grace
     * period, then releases the supervisor. Callers that already drained for the grace use this.
     */
    suspend fun settleNow() {
        cancelOutstanding()
        awaitSettled()
        supervisor.cancel()
    }

    /**
     * Idempotent shutdown: lets accepted work finish for the configured grace period, then cancels
     * what is unfinished and joins its finalization before application resources are released.
     */
    suspend fun shutdown() {
        closeAdmission()
        withTimeoutOrNull(config.shutdownGraceMillis) { awaitSettled() }
        settleNow()
    }

    private fun accepted(): List<BackgroundRecord> = synchronized(admissionLock) { ledger.toList() }

    private enum class Reservation { Granted, Full, Closed }

    private fun reserve(): Reservation =
        synchronized(admissionLock) {
            when {
                !accepting -> Reservation.Closed
                outstanding >= config.maxOutstandingTasks -> Reservation.Full
                else -> {
                    outstanding += 1
                    Reservation.Granted
                }
            }
        }

    private suspend fun supervise(record: BackgroundRecord) {
        try {
            val deadline = record.timeoutMillis
            if (deadline == null) {
                runBranch(record)
            } else if (withTimeoutOrNull(deadline) { runBranch(record); true } == null) {
                logTimeout(record, deadline)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (defect: Throwable) {
            logDefect(record, defect)
        }
    }

    private suspend fun runBranch(record: BackgroundRecord) {
        val semaphore = record.semaphore ?: return runBody(record)
        semaphore.acquire()
        record.permitHeld.set(true)
        runBody(record)
    }

    private suspend fun runBody(record: BackgroundRecord) {
        // coroutineScope joins every structured descendant - including processor-created children -
        // and their cancellation cleanup before the branch releases its claims.
        coroutineScope {
            val branchContext =
                record.context.copy(
                    event = record.event,
                    errorHandler = ResolvedErrorHandler.EMPTY,
                    coroutineScope = this,
                    streamingResources = record.registry,
                )
            val invocation = ManagedPolicyInvocation(record.application, record.binding, branchContext)
            try {
                when (val outcome = invocation.call(record.body, record.event)) {
                    is PolicyOutcome.Success -> Unit
                    is PolicyOutcome.Failure -> logTerminalFailure(record, outcome.error)
                }
            } finally {
                invocation.close()
            }
        }
    }

    /** One running limit per prepared binding, shared by every request that invokes it. */
    private fun permitFor(binding: Any, maxConcurrency: Int?): Semaphore? {
        val limit = maxConcurrency ?: return null
        return synchronized(admissionLock) { semaphores.getOrPut(binding) { Semaphore(limit) } }
    }

    /** One terminal diagnostic per modeled branch failure, carrying classification and metadata. */
    private fun logTerminalFailure(record: BackgroundRecord, error: MuseError) {
        report(
            "Background task {} failed for correlation {} on component {} with error type {}",
            record.taskId,
            record.event.correlationId,
            record.componentId,
            error.errorType,
        )
    }

    /** One terminal diagnostic per unexpected branch defect, classified as MULE:UNKNOWN. */
    private fun logDefect(record: BackgroundRecord, defect: Throwable) {
        report(
            "Background task {} failed unexpectedly for correlation {} on component {} with error type {}",
            record.taskId,
            record.event.correlationId,
            record.componentId,
            ErrorType.Mule.UNKNOWN,
            defect,
        )
    }

    /** One terminal diagnostic per expired deadline, classified as MULE:TIMEOUT. */
    private fun logTimeout(record: BackgroundRecord, deadline: Long) {
        report(
            "Background task {} timed out after {} ms for correlation {} on component {} with error type {}",
            record.taskId,
            deadline,
            record.event.correlationId,
            record.componentId,
            ErrorType.Mule.TIMEOUT,
        )
    }

    /**
     * Diagnostics are best-effort through the existing engine logger: no logging outcome may gate
     * permit, capacity, ledger or completion release.
     */
    private fun report(message: String, vararg arguments: Any?) {
        try {
            logger.error(message, *arguments)
        } catch (_: Throwable) {
            // A failing logging backend must not change task ownership.
        }
    }

    /** Releases every provisional claim and the reservation after a pre-acceptance failure. */
    private fun rollback(registry: StreamingResourceRegistry) {
        try {
            registry.releaseAll()
        } catch (cleanupFailure: Throwable) {
            report("Failed to release provisional background resources", cleanupFailure)
        } finally {
            // The reservation is returned even when the cleanup itself failed.
            synchronized(admissionLock) { outstanding -= 1 }
        }
    }

    /**
     * Finalizes exactly once, after the task's descendants and provider cleanup have finished. The
     * permit, the capacity slot, the ledger entry and the completion signal are released in
     * finally-style logic so diagnostics or cleanup failures can never skip them.
     */
    private fun finalize(record: BackgroundRecord) {
        if (!record.finalized.compareAndSet(false, true)) return
        try {
            releaseResources(record)
        } finally {
            try {
                if (record.permitHeld.get()) record.semaphore?.release()
            } finally {
                synchronized(admissionLock) {
                    outstanding -= 1
                    ledger -= record
                }
                record.completed.complete(Unit)
            }
        }
    }

    private fun releaseResources(record: BackgroundRecord) {
        try {
            record.registry.releaseAll()
        } catch (cleanupFailure: Throwable) {
            report("Failed to release background task {} resources", record.taskId, cleanupFailure)
        }
    }
}

private class BackgroundRecord(
    val application: PolicyBindings,
    val binding: Any,
    val semaphore: Semaphore?,
    val timeoutMillis: Long?,
    val body: BoundBody,
    val event: MuseEvent,
    val context: ExecutionContext,
    val registry: StreamingResourceRegistry,
) {
    val taskId: String = UUID.randomUUID().toString()
    val componentId: String? = context.currentComponent?.docId
    val completed = CompletableDeferred<Unit>()
    val finalized = AtomicBoolean(false)
    val permitHeld = AtomicBoolean(false)
    lateinit var job: Job
}

/** Exposes cancellation and finalization only; no event, outcome or failure graph is retained. */
private class BackgroundTaskHandle(
    private val job: Job,
    private val completed: CompletableDeferred<Unit>,
) : BackgroundTask {
    override fun cancel() {
        job.cancel()
    }

    override suspend fun join() {
        completed.await()
    }
}
