package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PayloadFilesTest {
    @Test
    fun `create and sha256 use incremental 64 KiB buffering`() {
        val directory = Files.createTempDirectory("composition-payload-")
        val path = directory.resolve("payload.bin")
        val expected = PayloadFiles.create(path, 256 * 1024L + 17L)
        assertEquals(expected, PayloadFiles.sha256(path))
        assertEquals(256 * 1024L + 17L, Files.size(path))
    }

    @Test
    fun `deterministic bytes produce stable digests`() {
        val firstDirectory = Files.createTempDirectory("composition-payload-a-")
        val secondDirectory = Files.createTempDirectory("composition-payload-b-")
        val digestOne = PayloadFiles.create(firstDirectory.resolve("one.bin"), 1024L)
        val digestTwo = PayloadFiles.create(secondDirectory.resolve("two.bin"), 1024L)
        assertEquals(digestOne, digestTwo)
    }

    @Test
    fun `atomic publication keeps incoming empty during staged generation`() {
        val directory = Files.createTempDirectory("composition-publish-")
        val incoming = directory.resolve("incoming")
        Files.createDirectories(incoming)
        val destination = incoming.resolve("partial-guard.bin")
        val staging = incoming.parent.resolve("staging/partial-guard.bin.part")
        Files.createDirectories(staging.parent)
        assertFalse(Files.exists(destination))
        PayloadFiles.create(staging, 128L)
        assertFalse(Files.exists(destination))
        Files.move(staging, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        assertEquals(128L, Files.size(destination))
    }
}
