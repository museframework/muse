package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.model.config.ConfigRef
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.model.WatermarkMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileListenerDslTest {
    @Test
    fun `listener defaults match the Mule file connector contract`() {
        val configuredFlow =
            flow("listener") {
                fileListener("files")
            }

        val listener = assertIs<FileListenerConfig>(configuredFlow.source)
        assertEquals("files", assertIs<ConfigRef.ByName>(listener.connectionConfig).name)
        assertEquals(".", listener.directory)
        assertTrue(listener.recursive)
        assertEquals(WatermarkMode.DISABLED, listener.watermarkMode)
        assertEquals(1_000, listener.pollingFrequencyMillis)
        assertEquals(0, listener.startDelayMillis)
        assertFalse(listener.autoDelete)
        assertTrue(listener.applyPostActionWhenFailed)
        assertFalse(listener.overwrite)
    }

    @Test
    fun `listener supports move post processing and watermarks`() {
        val connection = fileConnection { name = "files" }
        val configuredFlow =
            flow("listener") {
                fileListener(connection) {
                    directory = "incoming"
                    recursive = false
                    watermarkMode = WatermarkMode.MODIFIED_TIMESTAMP
                    pollingFrequencyMillis = 250
                    startDelayMillis = 5_000
                    moveToDirectory = "archive"
                    renameTo = "processed.txt"
                    overwrite = true
                }
            }

        val listener = assertIs<FileListenerConfig>(configuredFlow.source)
        assertEquals("incoming", listener.directory)
        assertFalse(listener.recursive)
        assertEquals(WatermarkMode.MODIFIED_TIMESTAMP, listener.watermarkMode)
        assertEquals(5_000, listener.startDelayMillis)
        assertEquals("archive", listener.moveToDirectory)
        assertEquals("processed.txt", listener.renameTo)
        assertTrue(listener.overwrite)
    }

    @Test
    fun `listener rejects a negative start delay`() {
        assertFailsWith<IllegalArgumentException> {
            flow("listener") {
                fileListener("files") {
                    startDelayMillis = -1
                }
            }
        }
    }

    @Test
    fun `listener rejects conflicting post processing actions`() {
        assertFailsWith<IllegalArgumentException> {
            flow("listener") {
                fileListener("files") {
                    autoDelete = true
                    moveToDirectory = "archive"
                }
            }
        }
    }

    @Test
    fun `listener rejects rename without a move directory`() {
        assertFailsWith<IllegalArgumentException> {
            flow("listener") {
                fileListener("files") {
                    renameTo = "processed.txt"
                }
            }
        }
    }
}
