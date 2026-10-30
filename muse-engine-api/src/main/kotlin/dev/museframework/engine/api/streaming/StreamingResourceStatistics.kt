package dev.museframework.engine.api.streaming

/**
 * Atomic ownership diagnostics for this JVM. Counts identities and registry/lease claims,
 * not open cursors, unmanaged resources, or bytes retained by a replay buffer.
 */
data class StreamingResourceStatistics(
    val activeProviderIdentities: Int,
    val registryClaims: Long,
    val cleanupInProgress: Int,
)
