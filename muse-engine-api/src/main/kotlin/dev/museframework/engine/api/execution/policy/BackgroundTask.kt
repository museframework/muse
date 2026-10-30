package dev.museframework.engine.api.execution.policy

/**
 * Application-owned accepted work, deliberately longer-lived than the invocation that submitted it.
 *
 * It exposes cancellation and finalization observation only: no branch outcome, event or failure
 * graph is retained for the caller.
 */
interface BackgroundTask {
    /** Cancels this task and its structured descendants; [join] observes the finalization. */
    fun cancel()

    /** Suspends until the task's descendants and resource cleanup have finished. */
    suspend fun join()
}
