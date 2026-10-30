package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.model.config.ConfigRef
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class FileConnectionDslTest {
    @Test
    fun `connection DSL uses its name as the configuration document name`() {
        val config =
            fileConnection {
                name = "files"
                workingDirectory = "/tmp/files"
                timeBetweenSizeCheck = 25
                timeBetweenSizeCheckUnit = TimeUnit.MILLISECONDS
            }

        assertEquals("files", config.name)
        assertEquals("files", config.docName)
        assertEquals("/tmp/files", config.workingDirectory)
        assertEquals(25, config.timeBetweenSizeCheck)
    }

    @Test
    fun `connection references preserve typed objects and names`() {
        val config = fileConnection { name = "files" }

        val direct = assertIs<ConfigRef.ByReference>(fileConnection(config))
        val named = assertIs<ConfigRef.ByName>(fileConnection("files"))

        assertSame(config, direct.config)
        assertEquals("files", named.name)
    }

    @Test
    fun `connection rejects a negative size check interval`() {
        assertFailsWith<IllegalArgumentException> {
            fileConnection {
                timeBetweenSizeCheck = -1
            }
        }
    }
}
