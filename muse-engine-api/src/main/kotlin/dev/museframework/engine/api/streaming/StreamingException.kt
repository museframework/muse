package dev.museframework.engine.api.streaming

open class StreamingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class StreamMaximumSizeExceededException(
    val limit: Long,
    val attemptedSize: Long,
) : StreamingException("Stream size $attemptedSize exceeds maximum configured size $limit")

class StreamingResourceReleasedException : StreamingException("Streaming resource has been released")

class StreamingProviderClosedException : StreamingException("Streaming provider is closed")

class StreamingCleanupException(
    val failures: List<Throwable>,
) : StreamingException("Failed to clean up ${failures.size} streaming resource operation(s)") {
    init {
        failures.forEach(::addSuppressed)
    }
}
