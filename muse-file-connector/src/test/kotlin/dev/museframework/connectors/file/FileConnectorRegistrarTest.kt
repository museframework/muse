package dev.museframework.connectors.file

import dev.museframework.engine.api.spi.MuseComponentRegistrar
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals

class FileConnectorRegistrarTest {
    @Test
    fun `runtime service metadata discovers exactly one file connector registrar`() {
        val registrars =
            ServiceLoader.load(MuseComponentRegistrar::class.java)
                .filterIsInstance<FileConnectorRegistrar>()
                .toList()

        assertEquals(1, registrars.size)
    }
}
