package dev.museframework.e2e.composition

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object PayloadFiles {
    private const val BUFFER_SIZE = 64 * 1024

    fun create(
        path: Path,
        sizeBytes: Long,
    ): String {
        require(sizeBytes >= 0L) { "sizeBytes must be non-negative but was $sizeBytes" }
        path.parent?.let(Files::createDirectories)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newOutputStream(path).use { output ->
            var remaining = sizeBytes
            val buffer = ByteArray(BUFFER_SIZE)
            var counter = 0L
            while (remaining > 0L) {
                val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                for (index in 0 until chunk) {
                    buffer[index] = ((counter + index) and 0xFF).toByte()
                }
                output.write(buffer, 0, chunk)
                digest.update(buffer, 0, chunk)
                counter += chunk
                remaining -= chunk
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
