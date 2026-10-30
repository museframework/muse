package dev.museframework.springboot

import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootVersion
import org.springframework.mock.env.MockEnvironment
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class MuseBannerPrinterTest {
    @Test
    fun `prints Muse logo followed by current Spring Boot version`() {
        val output = ByteArrayOutputStream()

        PrintStream(output, true, StandardCharsets.UTF_8).use { stream ->
            MuseBannerPrinter.printBanner(MockEnvironment(), null, stream)
        }

        val expected =
            listOf(
                " __  __",
                "|  \\/  |  _   _   ___  ___",
                "| |\\/| | | | | | / __|/ _ \\",
                "| |  | | | |_| | \\__ \\  __/",
                "|_|  |_|  \\__,_| |___/\\___|",
                "",
                "Powered by Spring Boot ${SpringBootVersion.getVersion()}",
                "",
                "",
            ).joinToString(System.lineSeparator())
        assertEquals(expected, output.toString(StandardCharsets.UTF_8))
    }
}
