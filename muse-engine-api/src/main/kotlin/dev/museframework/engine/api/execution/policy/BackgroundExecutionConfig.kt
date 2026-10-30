package dev.museframework.engine.api.execution.policy

/**
 * Application-wide limits for accepted background work, validated before the application starts.
 *
 * @param maxOutstandingTasks bounded capacity; every accepted queued or running task counts until
 *   its descendants and provider cleanup finish.
 * @param defaultTimeoutMillis deadline applied to a background declaration that sets no timeout;
 *   `null` adds no implicit deadline, preserving long-running intentional work.
 * @param shutdownGraceMillis time accepted work may finish after admission closes and before the
 *   application requests cancellation.
 */
data class BackgroundExecutionConfig(
    val maxOutstandingTasks: Int = 1024,
    val defaultTimeoutMillis: Long? = null,
    val shutdownGraceMillis: Long = 30_000,
)
