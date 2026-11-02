package dev.museframework.babelfish.mapper

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.exception.BabelfishTranslationException
import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MuleDocumentMapperTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `invalid global configuration reports its element and XML location`() {
        val invalidConfiguration = IllegalArgumentException("capacity must be positive")
        val registry = BabelfishRegistry()
        registry.registerConfig("test", "config", ConfigMapper { _, _ -> throw invalidConfiguration })
        val document = document("<test:config name=\"invalid\" capacity=\"-1\"/>")

        for (lenient in listOf(false, true)) {
            val failure = assertFailsWith<IllegalArgumentException> {
                MuleDocumentMapper(registry).map(document, lenient = lenient)
            }

            assertTrue(failure.message.orEmpty().contains("test:config"))
            assertTrue(failure.message.orEmpty().contains("line 2, col "))
            assertTrue(failure.message.orEmpty().contains("application.xml"))
            assertTrue(failure.message.orEmpty().contains("capacity must be positive"))
            assertSame(invalidConfiguration, failure.cause)
        }
    }

    @Test
    fun `unexpected configuration mapper failures also reach the caller`() {
        val mappingFailure = IllegalStateException("configuration mapping failed")
        val registry = BabelfishRegistry()
        registry.registerConfig("test", "config", ConfigMapper { _, _ -> throw mappingFailure })

        val failure = assertFailsWith<IllegalArgumentException> {
            MuleDocumentMapper(registry).map(document("<test:config name=\"broken\"/>"))
        }

        assertSame(mappingFailure, failure.cause)
        assertTrue(failure.message.orEmpty().contains("test:config"))
        assertTrue(failure.message.orEmpty().contains("line 2, col "))
    }

    @Test
    fun `a configuration mapper can defer an external reference explicitly`() {
        val registry = BabelfishRegistry()
        val mappedConfiguration = ConfigurationPropertiesConfig("dependent", "dependent", file = "app.yaml")
        registry.registerConfig("test", "config", ConfigMapper { config, ctx ->
            assertNull(ctx.resolveConfig(config.attrs.getValue("config-ref")))
            mappedConfiguration
        })

        val result = MuleDocumentMapper(registry).map(
            document("<test:config name=\"dependent\" config-ref=\"external\"/>"),
        )

        assertEquals(mapOf("dependent" to mappedConfiguration), result.usedConfigs)
    }

    @Test
    fun `unmapped optional configuration is skipped only in lenient mode`() {
        val document = document("<configuration-properties file=\"app.yaml\"/>")
        val mapper = MuleDocumentMapper(BabelfishRegistry())

        val failure = assertFailsWith<BabelfishTranslationException> { mapper.map(document) }
        assertEquals("configuration-properties", failure.errors.single().tag)
        assertTrue(mapper.map(document, lenient = true).usedConfigs.isEmpty())
    }

    @Test
    fun `a registered optional configuration mapper must still validate in lenient mode`() {
        val registry = BabelfishRegistry()
        val invalidConfiguration = IllegalArgumentException("file must not be blank")
        registry.registerConfig("", "configuration-properties", ConfigMapper { _, _ -> throw invalidConfiguration })

        val failure = assertFailsWith<IllegalArgumentException> {
            MuleDocumentMapper(registry).map(document("<configuration-properties file=\"\"/>"), lenient = true)
        }

        assertSame(invalidConfiguration, failure.cause)
        assertTrue(failure.message.orEmpty().contains("configuration-properties"))
        assertTrue(failure.message.orEmpty().contains("line 2, col "))
    }

    private fun document(globalConfiguration: String) =
        MuleXmlParser().parse(
            tempDir.resolve("application.xml").also {
                it.writeText(
                    """
                    <mule xmlns="http://www.mulesoft.org/schema/mule/core" xmlns:test="urn:test">
                        $globalConfiguration
                        <flow name="main"/>
                    </mule>
                    """.trimIndent(),
                )
            },
        )
}
