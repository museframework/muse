package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.babelfish.model.SourceLocation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ApikitConfigMapperTest {
    @Test
    fun `maps raml path and documentation metadata`() {
        val config =
            ApikitConfigMapper.map(
                rawConfig(
                    mapOf(
                        "name" to "orders-api",
                        "raml" to "api/orders.raml",
                        "doc:name" to "Orders API",
                        "doc:id" to "apikit-config-001",
                        "doc:description" to "Routes the orders API",
                    ),
                ),
                mappingContext(),
            )

        assertEquals("orders-api", config.name)
        assertEquals("api/orders.raml", config.spec)
        assertEquals("Orders API", config.docName)
        assertEquals("apikit-config-001", config.docId)
        assertEquals("Routes the orders API", config.docDescription)
        assertEquals(SOURCE_FILE.toString(), config.sourceFile)
    }

    @Test
    fun `accepts api as the specification path alias`() {
        val config =
            ApikitConfigMapper.map(
                rawConfig(mapOf("name" to "orders-api", "api" to "api/orders.yaml")),
                mappingContext(),
            )

        assertEquals("api/orders.yaml", config.spec)
    }

    @Test
    fun `rejects config without a specification path`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                ApikitConfigMapper.map(
                    rawConfig(mapOf("name" to "orders-api")),
                    mappingContext(),
                )
            }

        assertContains(exception.message.orEmpty(), "requires 'raml' or 'api' attribute")
    }

    @Test
    fun `emits the default validation mode without optional provenance`() {
        val config = ApikitConfig(name = "orders-api", spec = "api/orders.raml")
        val code = ApikitConfigCodeEmitter.emit(config, CodegenContext("com.example")).toString()

        assertFalse(config.disableValidations)
        assertFalse("disableValidations" in code)
        assertFalse("sourceFile" in code)
        assertFalse("sourceLine" in code)
        assertFalse("sourceClass" in code)
        assertFalse("docDescription" in code)
    }

    @Test
    fun `emits disabled validations and preserves provided provenance`() {
        val config = ApikitConfig(
            name = "orders-api",
            spec = "api/orders.raml",
            disableValidations = true,
            docDescription = "Orders \"API\"",
            sourceFile = "source\\orders.xml",
            sourceLine = 17,
            sourceClass = "Orders",
        )
        val code = ApikitConfigCodeEmitter.emit(config, CodegenContext("com.example")).toString()

        assertContains(code, "disableValidations = true")
        assertContains(code, "docDescription = \"Orders \\\"API\\\"\"")
        assertContains(code, "sourceFile = \"source\\\\orders.xml\"")
        assertContains(code, "sourceLine = 17")
        assertContains(code, "sourceClass = \"Orders\"")
    }

    private fun rawConfig(attrs: Map<String, String>): RawConfig =
        RawConfig(
            id = "internal-uuid",
            namespace = "apikit",
            tag = "config",
            attrs = attrs,
            children = emptyList(),
            location = SourceLocation(1, 1),
        )

    private fun mappingContext(): MappingContext = MappingContext(BabelfishRegistry(), SOURCE_FILE)

    private companion object {
        val SOURCE_FILE: Path = Path.of("src/test/resources/mule/apikit/apikit-router.xml")
    }
}
