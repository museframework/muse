package dev.museframework.engine.api.execution.policy

import dev.museframework.engine.api.event.MuseEvent

/** Request-owned structured work. Modeled failures are values; child defects fail the group. */
interface PolicyGroup {
    /** The optional positive timeout starts after a concurrency permit is acquired. */
    fun start(
        body: BodyHandle,
        event: MuseEvent,
        timeoutMillis: Long? = null,
    ): PolicyTask

    suspend fun await(task: PolicyTask): PolicyOutcome

    /** Requires a nonempty list of distinct tokens belonging to this open group. */
    suspend fun awaitAny(tasks: List<PolicyTask>): PolicyCompletion

    suspend fun cancelAndJoin(tasks: List<PolicyTask>)
}
