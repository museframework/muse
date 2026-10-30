package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.properties.PropertiesLoader.globalPropertyBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GlobalPropertyIT {

    private fun defaultFactory(): MuseComponentFactory {
        val factory = MuseComponentFactory()
        factory.registerConfig(GlobalProperty::class, globalPropertyBuilder)
        return factory
    }

    @Test
    fun `should store single property in repository`() {
        val globalProperties = listOf(
            GlobalProperty(
                docName = "Database Host",
                docId = "db-host",
                name = "db.host",
                value = "localhost"
            ),
            GlobalProperty(
                docName = "Database Port",
                docId = "db-port",
                name = "db.port",
                value = "5432"
            ),
            GlobalProperty(
                docName = "Environment",
                docId = "env",
                name = "env",
                value = "production"
            )
        )

        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = globalProperties
            )
        )

        val props = app.context.properties

        assertEquals("localhost", props.getProperty("db.host"))
        assertEquals("5432", props.getProperty("db.port"))
        assertEquals("production", props.getProperty("env"))
    }

    @Test
    fun `should retrieve property metadata by docName`() {
        val property = GlobalProperty(
            docName = "My Config Property",
            docId = "my-prop-001",
            name = "my.property",
            value = "my-value"
        )

        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = listOf(property)
            )
        )

        val retrieved = app.context.configs["My Config Property"]
        assertNotNull(retrieved)
        assertTrue(retrieved is GlobalProperty)
        assertEquals("my.property", (retrieved as GlobalProperty).name)
        assertEquals("my-value", retrieved.value)

        assertEquals("my-value", app.context.properties.getProperty("my.property"))
    }

    @Test
    fun `should handle property with special characters in value`() {
        val property = GlobalProperty(
            docName = "API URL",
            docId = "api-url",
            name = "api.url",
            value = "https://api.example.com:8080/v1/resource?key=abc123"
        )

        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = listOf(property)
            )
        )

        assertEquals(
            "https://api.example.com:8080/v1/resource?key=abc123",
            app.context.properties.getProperty("api.url")
        )
    }

    @Test
    fun `should list all property names`() {
        val properties = listOf(
            GlobalProperty(docName = "P1", docId = "p1", name = "prop1", value = "val1"),
            GlobalProperty(docName = "P2", docId = "p2", name = "prop2", value = "val2"),
            GlobalProperty(docName = "P3", docId = "p3", name = "prop3", value = "val3")
        )

        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = properties
            )
        )

        val propNames = app.context.properties.stringPropertyNames()
        assertTrue(propNames.contains("prop1"))
        assertTrue(propNames.contains("prop2"))
        assertTrue(propNames.contains("prop3"))
        assertEquals(3, propNames.size)
    }

    @Test
    fun `should return null for missing property`() {
        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = emptyList()
            )
        )

        assertNull(app.context.properties.getProperty("nonexistent"))
    }

    @Test
    fun `should support default value for missing property`() {
        val factory = defaultFactory()
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = emptyList()
            )
        )

        assertEquals("default", app.context.properties.getProperty("missing", "default"))
    }
}