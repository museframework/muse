package dev.museframework.engine.api.streaming

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists

class RepeatableFileStoreCursorStreamProvider private constructor(
    private val memoryBytes: ByteArray,
    private val fileSizeBytes: Long,
    private val tempFile: Path?,
    private val deleteFileOnClose: Boolean,
) : MuseCursorStreamProvider {
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val cursorLock = Any()
    private val cursors = mutableSetOf<FileStoreCursorStream>()

    override val isClosed: Boolean
        get() = closed.get()

    override val size: Long = memoryBytes.size.toLong() + fileSizeBytes

    override fun openCursor(): MuseCursorStream =
        synchronized(cursorLock) {
            if (closed.get()) throw StreamingProviderClosedException()
            if (released.get()) throw StreamingResourceReleasedException()
            FileStoreCursorStream(this, memoryBytes, tempFile).also { cursors.add(it) }
        }

    override fun close() {
        releaseResources()
    }

    override fun releaseResources() {
        val snapshot =
            synchronized(cursorLock) {
                if (!released.compareAndSet(false, true)) return
                closed.set(true)
                cursors.toList().also { cursors.clear() }
            }
        val failures = mutableListOf<Throwable>()
        snapshot.forEach { cursor ->
            try {
                cursor.release()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (deleteFileOnClose) {
            try {
                tempFile?.deleteIfExists()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        if (failures.isNotEmpty()) throw StreamingCleanupException(failures)
    }

    private fun unregister(cursor: FileStoreCursorStream) {
        synchronized(cursorLock) { cursors.remove(cursor) }
    }

    private class FileStoreCursorStream(
        override val provider: RepeatableFileStoreCursorStreamProvider,
        private val memoryBytes: ByteArray,
        private val tempFile: Path?,
    ) : MuseCursorStream() {
        private val closed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var fileChannel: java.nio.channels.SeekableByteChannel? = null
        private var index = 0L
        private val totalSize: Long = memoryBytes.size.toLong() + (tempFile?.let { Files.size(it) } ?: 0L)

        override val position: Long
            get() = index

        override val isReleased: Boolean
            get() = released.get()

        override fun read(): Int {
            ensureReadable()
            if (index >= totalSize) return -1
            if (index < memoryBytes.size.toLong()) {
                val result = memoryBytes[index.toInt()].toInt() and 0xff
                index++
                return result
            }
            val buffer = java.nio.ByteBuffer.allocate(1)
            val channel = fileChannel()
            channel.position(index - memoryBytes.size.toLong())
            val read = channel.read(buffer)
            if (read == -1) return -1
            index += read.toLong()
            buffer.flip()
            return buffer.get().toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureReadable()
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException("offset=$offset length=$length bufferSize=${buffer.size}")
            }
            if (length == 0) return 0
            if (index >= totalSize) return -1
            var remaining = length
            var targetOffset = offset
            var totalRead = 0
            if (index < memoryBytes.size.toLong() && remaining > 0) {
                val memoryIndex = index.toInt()
                val fromMemory = minOf(remaining, memoryBytes.size - memoryIndex)
                memoryBytes.copyInto(buffer, targetOffset, memoryIndex, memoryIndex + fromMemory)
                index += fromMemory.toLong()
                targetOffset += fromMemory
                remaining -= fromMemory
                totalRead += fromMemory
            }
            if (remaining > 0 && tempFile != null && index < totalSize) {
                val channel = fileChannel()
                channel.position(index - memoryBytes.size.toLong())
                val read = channel.read(java.nio.ByteBuffer.wrap(buffer, targetOffset, remaining))
                if (read > 0) {
                    index += read.toLong()
                    totalRead += read
                }
            }
            return if (totalRead == 0) -1 else totalRead
        }

        override fun seek(position: Long) {
            ensureReadable()
            require(position >= 0) { "position must be >= 0" }
            require(position <= totalSize) { "position must be <= stream size" }
            index = position
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    fileChannel?.close()
                } finally {
                    provider.unregister(this)
                }
            }
        }

        override fun release() {
            released.set(true)
            closed.set(true)
            try {
                fileChannel?.close()
            } finally {
                provider.unregister(this)
            }
        }

        private fun fileChannel(): java.nio.channels.SeekableByteChannel {
            val file = tempFile ?: throw IllegalStateException("No temp file is available")
            return fileChannel ?: Files.newByteChannel(file, java.nio.file.StandardOpenOption.READ).also { fileChannel = it }
        }

        private fun ensureReadable() {
            if (released.get() || provider.released.get()) throw StreamingResourceReleasedException()
            if (closed.get()) throw StreamingException("Cursor is closed")
        }
    }

    companion object {
        fun fromInputStream(
            inputStream: InputStream,
            inMemorySizeBytes: Int = 512 * 1024,
            tempDirectory: Path? = null,
            deleteFileOnClose: Boolean = true,
        ): RepeatableFileStoreCursorStreamProvider {
            require(inMemorySizeBytes >= 0) { "inMemorySizeBytes must be >= 0" }
            val memory = ByteArrayOutputStream(minOf(1024, inMemorySizeBytes.coerceAtLeast(32)))
            var tempFile: Path? = null
            var fileOutput: OutputStream? = null
            try {
                inputStream.use { source ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        val remainingMemory = inMemorySizeBytes - memory.size()
                        if (remainingMemory > 0) {
                            val toMemory = minOf(remainingMemory, read)
                            memory.write(buffer, 0, toMemory)
                            if (toMemory < read) {
                                val file = tempFile ?: createTempFile(tempDirectory).also { tempFile = it }
                                val output = fileOutput ?: openTempFileOutput(file).also { fileOutput = it }
                                output.write(buffer, toMemory, read - toMemory)
                            }
                        } else {
                            val file = tempFile ?: createTempFile(tempDirectory).also { tempFile = it }
                            val output = fileOutput ?: openTempFileOutput(file).also { fileOutput = it }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    fileOutput?.close()
                } catch (closeFailure: Exception) {
                    e.addSuppressed(closeFailure)
                }
                fileOutput = null
                try {
                    tempFile?.deleteIfExists()
                } catch (cleanupFailure: Exception) {
                    e.addSuppressed(cleanupFailure)
                }
                throw e
            }
            try {
                fileOutput?.close()
            } catch (e: Exception) {
                try {
                    tempFile?.deleteIfExists()
                } catch (cleanupFailure: Exception) {
                    e.addSuppressed(cleanupFailure)
                }
                throw e
            }
            val memoryBytes = memory.toByteArray()
            val fileSize =
                try {
                    tempFile?.let { Files.size(it) } ?: 0L
                } catch (e: Exception) {
                    try {
                        tempFile?.deleteIfExists()
                    } catch (cleanupFailure: Exception) {
                        e.addSuppressed(cleanupFailure)
                    }
                    throw e
                }
            return RepeatableFileStoreCursorStreamProvider(memoryBytes, fileSize, tempFile, deleteFileOnClose)
        }

        fun fromBytes(
            bytes: ByteArray,
            inMemorySizeBytes: Int = 512 * 1024,
            tempDirectory: Path? = null,
            deleteFileOnClose: Boolean = true,
        ): RepeatableFileStoreCursorStreamProvider =
            fromInputStream(ByteArrayInputStream(bytes), inMemorySizeBytes, tempDirectory, deleteFileOnClose)

        private fun createTempFile(tempDirectory: Path?): Path =
            if (tempDirectory == null) {
                Files.createTempFile("muse-stream-", ".tmp")
            } else {
                Files.createDirectories(tempDirectory)
                Files.createTempFile(tempDirectory, "muse-stream-", ".tmp")
            }

        private fun openTempFileOutput(file: Path): OutputStream =
            Files.newOutputStream(
                file,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            )
    }
}
