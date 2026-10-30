package dev.museframework.plugin.core.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeResolverTest {

    @Test
    fun `explicit config takes priority over detection`() {
        val result = RuntimeResolver.resolve("spring-boot", emptyList())
        assertEquals(MuseRuntime.SpringBoot, result)
    }

    @Test
    fun `explicit config is case-insensitive`() {
        assertEquals(MuseRuntime.SpringBoot, RuntimeResolver.resolve("Spring-Boot", emptyList()))
        assertEquals(MuseRuntime.SpringBoot, RuntimeResolver.resolve("SPRING-BOOT", emptyList()))
    }

    @Test
    fun `throws for unknown explicit config`() {
        assertThrows<MuseRuntimeException> {
            RuntimeResolver.resolve("weblogic", emptyList())
        }
    }

    @Test
    fun `throws with helpful message on ambiguity`() {
        val ex = assertThrows<MuseRuntimeException> {
            RuntimeResolver.resolve(null, listOf(
                MavenArtifact("a", "muse-spring-boot", "compile"),
                MavenArtifact("b", "quarkus-core", "compile")
            ))
        }
        assertTrue(ex.message!!.contains("<runtime>"), "Error message must contain <runtime>")
    }

    @Test
    fun `returns None when nothing found`() {
        val result = RuntimeResolver.resolve(null, emptyList())
        assertEquals(MuseRuntime.None, result)
    }
}
