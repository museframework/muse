package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.StreamingStrategy
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "muse.streaming")
data class MuseHttpStreamingProperties(
    var strategy: String = "repeatable-file-store",
    val inMemory: InMemoryProperties = InMemoryProperties(),
    val fileStore: FileStoreProperties = FileStoreProperties(),
    val legacyBodyMaterialization: Boolean = false,
) {
    data class InMemoryProperties(
        val initialBufferSizeBytes: Int = 512 * 1024,
        val bufferSizeIncrementBytes: Int = 512 * 1024,
        val maxBufferSizeBytes: Int = 1024 * 1024,
        val initialObjectBufferSize: Int = 500,
        val objectBufferSizeIncrement: Int = 100,
        val maxObjectBufferSize: Int = 1000,
    )

    data class FileStoreProperties(
        val inMemorySizeBytes: Int = 512 * 1024,
        val tempDirectory: String? = null,
        val deleteFilesOnClose: Boolean = true,
    )

    fun toStrategy(): StreamingStrategy {
        val resolved =
            when (strategy) {
                "repeatable-in-memory" ->
                    StreamingStrategy.RepeatableInMemory(
                        initialBufferSizeBytes = inMemory.initialBufferSizeBytes,
                        bufferSizeIncrementBytes = inMemory.bufferSizeIncrementBytes,
                        maxBufferSizeBytes = inMemory.maxBufferSizeBytes,
                        initialObjectBufferSize = inMemory.initialObjectBufferSize,
                        objectBufferSizeIncrement = inMemory.objectBufferSizeIncrement,
                        maxObjectBufferSize = inMemory.maxObjectBufferSize,
                    )

                "repeatable-file-store" -> toFileStoreStrategy()

                "non-repeatable" -> StreamingStrategy.NonRepeatable
                else -> throw IllegalArgumentException("Unknown streaming strategy: $strategy")
            }
        require(!legacyBodyMaterialization || resolved !is StreamingStrategy.NonRepeatable) {
            "muse.streaming.legacy-body-materialization cannot be enabled with the non-repeatable strategy"
        }
        return resolved
    }

    fun toFileStoreStrategy(): StreamingStrategy.RepeatableFileStore =
        StreamingStrategy.RepeatableFileStore(
            inMemorySizeBytes = fileStore.inMemorySizeBytes,
            tempDirectory = fileStore.tempDirectory,
            deleteFilesOnClose = fileStore.deleteFilesOnClose,
        )
}
