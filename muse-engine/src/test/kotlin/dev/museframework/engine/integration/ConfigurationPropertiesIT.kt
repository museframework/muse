package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.config.ConfigurationProperties
import dev.museframework.dsl.api.model.config.Configuration
import dev.museframework.dsl.api.model.config.GlobalProperty
import dev.museframework.engine.app.MuseApplicationConfig
import dev.museframework.engine.app.MuseBootstrapException
import dev.museframework.engine.app.bootstrap
import dev.museframework.engine.factory.MuseComponentFactory
import dev.museframework.engine.properties.PropertiesLoader.configurationPropertiesBuilder
import dev.museframework.engine.properties.PropertiesLoader.globalPropertyBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class ConfigurationPropertiesIT {

    @Test
    fun `should apply all property sources before configs retaining declaration order`() {
        val applied = mutableListOf<String>()
        val factory = defaultFactory()
        factory.registerConfig(Configuration::class) { config, context ->
            applied += "${config.docId}:${context.properties.getProperty("shared")}"
        }
        bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = listOf(
                    Configuration(docName = "Shared config label", docId = "first"),
                    GlobalProperty(docName = "Shared property label", docId = "one", name = "shared", value = "one"),
                    Configuration(docName = "Shared config label", docId = "second"),
                    GlobalProperty(docName = "Shared property label", docId = "two", name = "shared", value = "two"),
                ),
            ),
        )

        assertEquals(listOf("first:two", "second:two"), applied)
    }

    @Test
    fun `should apply every config even when documentation names are identical`() {
        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = defaultFactory(),
                configs = listOf(
                    GlobalProperty(docName = "Shared label", docId = "first", name = "first", value = "one"),
                    GlobalProperty(docName = "Shared label", docId = "second", name = "second", value = "two"),
                ),
            ),
        )

        assertEquals("one", app.context.properties.getProperty("first"))
        assertEquals("two", app.context.properties.getProperty("second"))
    }

    private fun defaultFactory(): MuseComponentFactory {
        val factory = MuseComponentFactory()
        factory.registerConfig(GlobalProperty::class, globalPropertyBuilder)
        factory.registerConfig(ConfigurationProperties::class, configurationPropertiesBuilder)
        return factory
    }

    @Test
    fun `should load properties from file into repository`() {
        val factory = defaultFactory()
        val propsContent = """
            service.host=api.example.com
            service.port=8080
            service.timeout=30000
        """.trimIndent()

        val tempFile = File.createTempFile("test-config", ".properties")
        tempFile.writeText(propsContent)
        tempFile.deleteOnExit()

        val configProperties = listOf(
            ConfigurationProperties(
                docName = "App Config",
                docId = "app-config",
                file = "file:" + tempFile.absolutePath
            )
        )

        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = configProperties
            )
        )

        val props = app.context.properties

        assertEquals("api.example.com", props.getProperty("service.host"))
        assertEquals("8080", props.getProperty("service.port"))
        assertEquals("30000", props.getProperty("service.timeout"))
    }

    @Test
    fun `should retrieve configuration properties metadata by docName`() {
        val factory = defaultFactory()
        val propsContent = "app.name=TestApp"

        val tempFile = File.createTempFile("my-config", ".properties")
        tempFile.writeText(propsContent)
        tempFile.deleteOnExit()

        val config = ConfigurationProperties(
            docName = "My Properties File",
            docId = "my-prop-file",
            file = "file:" + tempFile.absolutePath
        )

        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = listOf(config)
            )
        )

        val retrieved = app.context.configs["My Properties File"]
        assertNotNull(retrieved)
        assertTrue(retrieved is ConfigurationProperties)
        assertEquals("file:" + tempFile.absolutePath, (retrieved as ConfigurationProperties).file)

        assertEquals("TestApp", app.context.properties.getProperty("app.name"))
    }

    @Test
    fun `should merge GlobalProperty and ConfigurationProperties into single repository`() {
        val factory = defaultFactory()
        val propsContent = """
            db.host=db.example.com
            db.port=3306
        """.trimIndent()

        val tempFile = File.createTempFile("db-config", ".properties")
        tempFile.writeText(propsContent)
        tempFile.deleteOnExit()

        val globalProp = GlobalProperty(
            docName = "Global Setting",
            docId = "global-001",
            name = "setting.name",
            value = "setting-value"
        )

        val configProps = ConfigurationProperties(
            docName = "External Config",
            docId = "ext-config",
            file = "file:" + tempFile.absolutePath
        )

        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = listOf(globalProp, configProps)
            )
        )

        val props = app.context.properties

        assertEquals("setting-value", props.getProperty("setting.name"))
        assertEquals("db.example.com", props.getProperty("db.host"))
        assertEquals("3306", props.getProperty("db.port"))
    }

    @Test
    fun `should throw MuseBootstrapException when configuration file does not exist`() {
        val factory = defaultFactory()
        val configProperties = listOf(
            ConfigurationProperties(
                docName = "Missing Config",
                docId = "missing-config",
                file = "file:/non/existent/config.properties"
            )
        )

        val exception = assertThrows<MuseBootstrapException> {
            bootstrap(
                MuseApplicationConfig(
                    flows = emptyList(),
                    factory = factory,
                    configs = configProperties
                )
            )
        }

        assertEquals(
            "Bootstrap failed with 1 error(s):\n  - File not found: /non/existent/config.properties",
            exception.message
        )
    }

    @Test
    fun `should load multiple property files with later files overwriting earlier`() {
        val factory = defaultFactory()
        val file1Content = "shared.key=value1\nunique.key1=unique1"
        val file2Content = "shared.key=value2\nunique.key2=unique2"

        val tempFile1 = File.createTempFile("config1", ".properties")
        tempFile1.writeText(file1Content)
        tempFile1.deleteOnExit()

        val tempFile2 = File.createTempFile("config2", ".properties")
        tempFile2.writeText(file2Content)
        tempFile2.deleteOnExit()

        val configProperties = listOf(
            ConfigurationProperties(
                docName = "Config 1",
                docId = "config-1",
                file = "file:" + tempFile1.absolutePath
            ),
            ConfigurationProperties(
                docName = "Config 2",
                docId = "config-2",
                file = "file:" + tempFile2.absolutePath
            )
        )

        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = configProperties
            )
        )

        val props = app.context.properties
        assertEquals("value2", props.getProperty("shared.key"))
        assertEquals("unique1", props.getProperty("unique.key1"))
        assertEquals("unique2", props.getProperty("unique.key2"))
    }

    @Test
    fun `should process configs in order with later overwriting earlier`() {
        val factory = defaultFactory()
        val fileContent = "override.key=from-file"

        val tempFile = File.createTempFile("config", ".properties")
        tempFile.writeText(fileContent)
        tempFile.deleteOnExit()

        val configs = listOf(
            GlobalProperty(
                docName = "Inline",
                docId = "inline",
                name = "override.key",
                value = "from-inline"
            ),
            ConfigurationProperties(
                docName = "File Config",
                docId = "file-config",
                file = "file:" + tempFile.absolutePath
            )
        )

        val app = bootstrap(
            MuseApplicationConfig(
                flows = emptyList(),
                factory = factory,
                configs = configs
            )
        )

        assertEquals("from-file", app.context.properties.getProperty("override.key"))
    }
}
