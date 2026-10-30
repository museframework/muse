package dev.museframework.engine.properties

import dev.museframework.dsl.api.model.config.EnvironmentProperties
import dev.museframework.dsl.api.model.config.GlobalProperty
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PropertiesLoaderTest {
    @Test
    fun `later relaxed name replaces an earlier equivalent property`() {
        val properties = Properties().apply { setProperty("db.host", "localhost") }

        PropertiesLoader.apply(
            GlobalProperty(
                docName = "Database host override",
                name = "DB_HOST",
                value = "prod.db.example.com",
            ),
            properties,
        )

        assertEquals("prod.db.example.com", PropertiesLoader.resolveProperty("db.host", properties))
        assertEquals("prod.db.example.com", properties.getProperty("DB_HOST"))
        assertNull(properties.getProperty("db.host"))
    }

    @Test
    fun `environment loading can use a deterministic source with prefix removal`() {
        val properties = Properties()
        val environment =
            mapOf(
                "APP_DB_HOST" to "db.internal",
                "IGNORED_VALUE" to "ignored",
            )

        PropertiesLoader.apply(
            EnvironmentProperties(
                docName = "Application environment",
                prefix = "APP_",
                removePrefix = true,
            ),
            properties,
            environment,
        )

        assertEquals("db.internal", properties.getProperty("DB_HOST"))
        assertEquals("db.internal", PropertiesLoader.resolveProperty("db.host", properties))
        assertFalse(properties.containsKey("IGNORED_VALUE"))
    }
}
