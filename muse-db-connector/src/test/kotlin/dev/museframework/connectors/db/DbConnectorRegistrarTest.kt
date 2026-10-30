package dev.museframework.connectors.db

import dev.museframework.engine.api.spi.MuseComponentRegistrar
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals

class DbConnectorRegistrarTest {
    @Test
    fun `runtime registrar is discoverable exactly once`() {
        val registrars =
            ServiceLoader.load(MuseComponentRegistrar::class.java, DbConnectorRegistrar::class.java.classLoader)
                .filterIsInstance<DbConnectorRegistrar>()

        assertEquals(1, registrars.size)
    }
}
