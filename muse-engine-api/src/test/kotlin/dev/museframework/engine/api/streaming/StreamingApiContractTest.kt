package dev.museframework.engine.api.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamingApiContractTest {

    @Test
    fun `streaming strategies expose in memory file store and non repeatable options`() {
        val inMemory = StreamingStrategy.RepeatableInMemory()
        val fileStore = StreamingStrategy.RepeatableFileStore()
        val nonRepeatable = StreamingStrategy.NonRepeatable

        assertEquals(512 * 1024, inMemory.initialBufferSizeBytes)
        assertEquals(1024 * 1024, inMemory.maxBufferSizeBytes)
        assertEquals(512 * 1024, fileStore.inMemorySizeBytes)
        assertIs<StreamingStrategy.NonRepeatable>(nonRepeatable)
    }

    @Test
    fun `cursor provider contracts support byte and object cursors`() {
        class EmptyStreamCursor(
            override val provider: MuseCursorProvider<out MuseCursor>,
        ) : MuseCursorStream() {
                override val position: Long = 0L
                override val isReleased: Boolean = false
                override fun read(): Int = -1
                override fun seek(position: Long) {}
                override fun release() {}
        }
        class EmptyStreamProvider : MuseCursorStreamProvider {
            override val isClosed: Boolean = false
            override fun openCursor(): MuseCursorStream = EmptyStreamCursor(this)
            override fun close() {}
            override fun releaseResources() {}
        }

        class EmptyIteratorCursor(
            override val provider: MuseCursorProvider<out MuseCursor>,
        ) : MuseCursorIterator<String> {
                override val position: Long = 0L
                override val isReleased: Boolean = false
                override fun hasNext(): Boolean = false
                override fun next(): String = throw NoSuchElementException()
                override fun seek(position: Long) {}
                override fun close() {}
                override fun release() {}
        }
        class EmptyIteratorProvider : MuseCursorIteratorProvider<String> {
            override val isClosed: Boolean = false
            override fun openCursor(): MuseCursorIterator<String> = EmptyIteratorCursor(this)
            override fun close() {}
            override fun releaseResources() {}
        }

        val streamProvider = EmptyStreamProvider()
        val iteratorProvider = EmptyIteratorProvider()

        assertEquals(-1L, streamProvider.size)
        assertEquals(-1L, iteratorProvider.size)
    }

    @Test
    fun `non repeatable strategy creates provider without throwing`() {
        val provider = StreamingProviders.byteProvider(
            inputStream = java.io.ByteArrayInputStream("test".toByteArray()),
            strategy = StreamingStrategy.NonRepeatable,
        )

        provider.use {
            assertEquals("test", provider.openCursor().use { it.readBytes().decodeToString() })
            kotlin.test.assertFailsWith<IllegalStateException> { provider.openCursor() }
        }
    }
}
