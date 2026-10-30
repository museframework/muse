package dev.museframework.engine.api.streaming

import java.io.InputStream

object StreamingProviders {
    /**
     * Adopts a raw provider. Publish and use the returned handle instead of its
     * delegate: only the handle and its open cursors protect application use.
     */
    fun managed(provider: MuseCursorStreamProvider): MuseCursorStreamProvider = ManagedStreamingProviders.stream(provider)

    /** Adopts an iterator provider without consuming or traversing its values. */
    fun <T> managed(provider: MuseCursorIteratorProvider<T>): MuseCursorIteratorProvider<T> = ManagedStreamingProviders.iterator(provider)

    fun byteProvider(
        inputStream: InputStream,
        strategy: StreamingStrategy = StreamingStrategy.RepeatableInMemory(),
    ): MuseCursorStreamProvider {
        val provider: MuseCursorStreamProvider = when (strategy) {
            is StreamingStrategy.RepeatableInMemory ->
                RepeatableInMemoryCursorStreamProvider.fromInputStream(
                    inputStream = inputStream,
                    initialBufferSizeBytes = strategy.initialBufferSizeBytes,
                    bufferSizeIncrementBytes = strategy.bufferSizeIncrementBytes,
                    maxBufferSizeBytes = strategy.maxBufferSizeBytes,
                )

            is StreamingStrategy.RepeatableFileStore ->
                RepeatableFileStoreCursorStreamProvider.fromInputStream(
                    inputStream = inputStream,
                    inMemorySizeBytes = strategy.inMemorySizeBytes,
                    tempDirectory = strategy.tempDirectory?.let { java.nio.file.Path.of(it) },
                    deleteFileOnClose = strategy.deleteFilesOnClose,
                )

            StreamingStrategy.NonRepeatable ->
                NonRepeatableCursorStreamProvider(inputStream)
        }
        // These concrete factory products expose size as passive scalar metadata.
        return ManagedStreamingProviders.stream(provider, provider.size)
    }

    fun <T> objectProvider(
        iterable: Iterable<T>,
        strategy: StreamingStrategy = StreamingStrategy.RepeatableInMemory(),
    ): MuseCursorIteratorProvider<T> {
        val provider: MuseCursorIteratorProvider<T> = when (strategy) {
            is StreamingStrategy.RepeatableInMemory ->
                RepeatableInMemoryCursorIteratorProvider.fromIterable(
                    iterable = iterable,
                    initialObjectBufferSize = strategy.initialObjectBufferSize,
                    objectBufferSizeIncrement = strategy.objectBufferSizeIncrement,
                    maxObjectBufferSize = strategy.maxObjectBufferSize,
                )

            is StreamingStrategy.RepeatableFileStore ->
                throw UnsupportedOperationException("Repeatable file-store object streams are not part of the MVP")

            StreamingStrategy.NonRepeatable ->
                NonRepeatableCursorIteratorProvider(iterable.iterator())
        }
        return ManagedStreamingProviders.iterator(provider, provider.size)
    }
}
