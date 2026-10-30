package dev.museframework.connectors.http.spring

import dev.museframework.engine.api.streaming.StreamingStrategy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MuseHttpStreamingPropertiesTest {
    @Test
    fun `maps every repeatable in-memory setting`() {
        val properties =
            MuseHttpStreamingProperties(
                strategy = "repeatable-in-memory",
                inMemory =
                    MuseHttpStreamingProperties.InMemoryProperties(
                        initialBufferSizeBytes = 8,
                        bufferSizeIncrementBytes = 16,
                        maxBufferSizeBytes = 64,
                        initialObjectBufferSize = 2,
                        objectBufferSizeIncrement = 3,
                        maxObjectBufferSize = 11,
                    ),
            )

        val strategy = assertIs<StreamingStrategy.RepeatableInMemory>(properties.toStrategy())

        assertEquals(8, strategy.initialBufferSizeBytes)
        assertEquals(16, strategy.bufferSizeIncrementBytes)
        assertEquals(64, strategy.maxBufferSizeBytes)
        assertEquals(2, strategy.initialObjectBufferSize)
        assertEquals(3, strategy.objectBufferSizeIncrement)
        assertEquals(11, strategy.maxObjectBufferSize)
    }

    @Test
    fun `rejects legacy body materialization for a non-repeatable stream`() {
        val properties =
            MuseHttpStreamingProperties(
                strategy = "non-repeatable",
                legacyBodyMaterialization = true,
            )

        val failure = assertFailsWith<IllegalArgumentException> { properties.toStrategy() }

        assertEquals(
            "muse.streaming.legacy-body-materialization cannot be enabled with the non-repeatable strategy",
            failure.message,
        )
    }

    @Test
    fun `maps file store settings for request response detachment`() {
        val properties =
            MuseHttpStreamingProperties(
                strategy = "non-repeatable",
                fileStore =
                    MuseHttpStreamingProperties.FileStoreProperties(
                        inMemorySizeBytes = 7,
                        tempDirectory = "/tmp/muse-property-test",
                        deleteFilesOnClose = false,
                    ),
            )

        val strategy = properties.toFileStoreStrategy()

        assertEquals(7, strategy.inMemorySizeBytes)
        assertEquals("/tmp/muse-property-test", strategy.tempDirectory)
        assertEquals(false, strategy.deleteFilesOnClose)
    }
}
