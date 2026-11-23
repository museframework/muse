package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RemoveVariableMapperTest {
    @Test
    fun `variable name and documentation metadata survive mapping and Kotlin emission`() {
        val raw = RawElement(
            namespace = "",
            tag = "remove-variable",
            attrs = mapOf("variableName" to "temporary", "doc:id" to "remove-01", "doc:name" to "Remove temporary value"),
            children = emptyList(),
            location = SourceLocation(1, 1),
        )
        val config = RemoveVariableMapper.map(raw, MappingContext(BabelfishRegistry(), Path.of("remove-variable.xml")))
        assertEquals("remove-01", config.docId)
        assertEquals("Remove temporary value", config.docName)

        val code = emitCore(config)
        assertContains(code, "import dev.museframework.dsl.dsl.removeVariable")
        assertContains(code, "removeVariable(\"temporary\") {")
        assertContains(code, "docId = \"remove-01\"")
        assertContains(code, "docName = \"Remove temporary value\"")
    }
}
