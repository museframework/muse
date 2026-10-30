package dev.museframework.engine.execution.policy

import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.engine.api.error.MuseError
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.policy.BodyHandle
import dev.museframework.engine.api.execution.policy.PolicyCompletion
import dev.museframework.engine.api.execution.policy.PolicyGroup
import dev.museframework.engine.api.execution.policy.PolicyOutcome
import dev.museframework.engine.api.execution.policy.PolicyTask
import dev.museframework.engine.api.streaming.StreamingResourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

internal class ManagedPolicyGroup(
    private val invocation: ManagedPolicyInvocation,
    private val scope: CoroutineScope,
    private val context: ExecutionContext,
    maxConcurrency: Int?,
) : PolicyGroup {
    private val semaphore = maxConcurrency?.let(::Semaphore)
    private val tasks = mutableListOf<Task>()
    private var open = true

    private class Task(val group: ManagedPolicyGroup) : PolicyTask {
        lateinit var deferred: Deferred<PolicyOutcome>
        var timerExpired = false
    }

    override fun start(body: BodyHandle, event: MuseEvent, timeoutMillis: Long?): PolicyTask {
        checkOpen()
        invocation.validateBody(body)
        require(timeoutMillis == null || timeoutMillis > 0) { "Task timeout must be positive" }
        // Claim the submitted input before dispatch, including work waiting for a permit.
        invocation.retain(event)
        val task = Task(this)
        task.deferred = scope.async {
            semaphore?.acquire()
            try {
                val resources = StreamingResourceRegistry()
                try {
                    val outcome = if (timeoutMillis == null) runBody(body, event, resources)
                    else withTimeoutOrNull(timeoutMillis) { runBody(body, event, resources) }
                    val terminal = outcome ?: run {
                        currentCoroutineContext().ensureActive()
                        task.timerExpired = true
                        timeout(event, timeoutMillis!!)
                    }
                    // Adoption precedes release: the public result can outlive this child's claim.
                    invocation.retain(terminal.event)
                    terminal
                } finally {
                    resources.releaseAll()
                }
            } finally { semaphore?.release() }
        }
        tasks += task
        return task
    }

    override suspend fun await(task: PolicyTask): PolicyOutcome = owned(task).deferred.await()

    override suspend fun awaitAny(tasks: List<PolicyTask>): PolicyCompletion {
        checkOpen()
        require(tasks.isNotEmpty()) { "Await-any requires at least one task" }
        val selected = tasks.map(::owned)
        require(selected.distinct().size == selected.size) { "Await-any requires distinct tasks" }
        return select {
            selected.forEach { task ->
                task.deferred.onAwait { PolicyCompletion(task, it) }
            }
        }
    }

    override suspend fun cancelAndJoin(tasks: List<PolicyTask>) {
        checkOpen()
        cancelAndJoinOwned(tasks.map(::owned))
    }

    /** Provenance for the standard scatter policy only; domain TIMEOUT failures do not set this. */
    internal fun timerExpired(task: PolicyTask): Boolean = owned(task).timerExpired

    internal suspend fun close() {
        open = false
        cancelAndJoinOwned(tasks)
    }

    private suspend fun cancelAndJoinOwned(tasks: List<Task>) = withContext(NonCancellable) {
        tasks.forEach { it.deferred.cancel() }
        tasks.map { it.deferred }.joinAll()
    }

    private fun owned(task: PolicyTask): Task {
        checkOpen()
        require(task is Task && task.group === this) { "Task does not belong to this group" }
        return task
    }

    private fun checkOpen() { check(open) { "Policy group is closed" } }

    private suspend fun runBody(
        body: BodyHandle,
        event: MuseEvent,
        resources: StreamingResourceRegistry,
    ): PolicyOutcome = coroutineScope {
        val child = invocation.child(context.copy(
            event = event, coroutineScope = this, streamingResources = resources,
        ))
        try { child.call(body, event) } finally { child.close() }
        // This scope joins structured descendants, including their cancellation cleanup,
        // before the task releases resources or its concurrency permit.
    }

    private fun timeout(event: MuseEvent, millis: Long): PolicyOutcome.Failure {
        val description = "Policy task timed out after $millis ms"
        val error = MuseError(
            description = description,
            detailedDescription = "Policy task exceeded its configured timeout of $millis ms",
            errorType = ErrorType.Mule.TIMEOUT,
            cause = TimeoutException(description),
            sourceFile = context.currentComponent?.sourceFile,
            sourceLine = context.currentComponent?.sourceLine,
            sourceClass = context.currentComponent?.sourceClass,
        )
        return PolicyOutcome.Failure(error, event.copy(error = error))
    }
}
