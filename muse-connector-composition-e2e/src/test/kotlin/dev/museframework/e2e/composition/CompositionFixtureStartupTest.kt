package dev.museframework.e2e.composition

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CompositionFixtureStartupTest {
    @Test
    fun `startup failure unwinds external servers and directories`() {
        val directory = Files.createTempDirectory("composition-startup-failure-")
        val missingJar = directory.resolve("missing.jar")
        val log = directory.resolve("startup.log")
        val fixtureRoot = directory.resolve("fixture")
        assertFailsWith<IllegalArgumentException> {
            CompositionFixture.start(
                fixtureRoot,
                applicationJar = missingJar,
                applicationLog = log,
            )
        }
        assertFalse(Files.exists(fixtureRoot.resolve("h2")))
        assertFalse(Files.exists(fixtureRoot.resolve("sftp")))
    }
}
