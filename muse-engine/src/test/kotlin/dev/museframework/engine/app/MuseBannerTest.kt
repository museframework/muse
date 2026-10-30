package dev.museframework.engine.app

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class MuseBannerTest {
    @Test
    fun `prints Muse logo followed by platform and version`() {
        val output = ByteArrayOutputStream()

        PrintStream(output, true, StandardCharsets.UTF_8).use { stream ->
            MuseBanner.printTo(stream, platform = "Spring Boot", version = "4.0.3")
        }

        val expected =
            listOf(
                " __  __",
                "|  \\/  |  _   _   ___  ___",
                "| |\\/| | | | | | / __|/ _ \\",
                "| |  | | | |_| | \\__ \\  __/",
                "|_|  |_|  \\__,_| |___/\\___|",
                "",
                "Powered by Spring Boot 4.0.3",
                "",
                "",
            ).joinToString(System.lineSeparator())
        assertEquals(expected, output.toString(StandardCharsets.UTF_8))
    }
}
