package dev.museframework.engine.api.execution

import dev.museframework.engine.api.error.MuseError

/**
 * Terminal outcome of one processor invocation.
 *
 * Cancellation is delivered as coroutine cancellation and programming defects as exceptions:
 * neither is modeled here, and no deferred instruction, boundary or owner token belongs in a result.
 */
sealed class ProcessorResult {
    data class Success(
        val context: ExecutionContext,
    ) : ProcessorResult()

    data class Failure(
        val error: MuseError,
        val context: ExecutionContext,
    ) : ProcessorResult()
}
