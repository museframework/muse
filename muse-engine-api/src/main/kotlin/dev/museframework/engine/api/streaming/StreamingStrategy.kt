package dev.museframework.engine.api.streaming

sealed interface StreamingStrategy {
    data class RepeatableInMemory(
        val initialBufferSizeBytes: Int = 512 * 1024,
        val bufferSizeIncrementBytes: Int = 512 * 1024,
        val maxBufferSizeBytes: Int = 1024 * 1024,
        val initialObjectBufferSize: Int = 500,
        val objectBufferSizeIncrement: Int = 100,
        val maxObjectBufferSize: Int = 1000,
    ) : StreamingStrategy

    data class RepeatableFileStore(
        val inMemorySizeBytes: Int = 512 * 1024,
        val tempDirectory: String? = null,
        val deleteFilesOnClose: Boolean = true,
    ) : StreamingStrategy

    data object NonRepeatable : StreamingStrategy
}
