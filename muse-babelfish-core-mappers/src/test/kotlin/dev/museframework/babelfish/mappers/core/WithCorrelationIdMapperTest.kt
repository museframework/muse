package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class WithCorrelationIdMapperTest {
    @Test
    fun `correlation expression and documentation metadata survive mapping and Kotlin emission`() {
        val raw = RawElement(
            namespace = "",
            tag = "with-correlation-id",
            attrs = mapOf(
                "value" to "#[vars.correlationId]",
                "doc:id" to "correlation-01",
                "doc:name" to "Set correlation ID",
            ),
            children = emptyList(),
            location = SourceLocation(1, 1),
        )
        val config = WithCorrelationIdMapper.map(raw, MappingContext(BabelfishRegistry(), Path.of("with-correlation-id.xml")))
        assertEquals("correlation-01", config.docId)
        assertEquals("Set correlation ID", config.docName)

        val code = emitCore(config)
        assertContains(code, "import dev.museframework.dsl.builder.withCorrelationId")
        assertContains(code, "withCorrelationId {")
        assertContains(code, "docId = \"correlation-01\"")
        assertContains(code, "docName = \"Set correlation ID\"")
        assertContains(code, "correlationId = \"#[vars.correlationId]\"")
    }
}
