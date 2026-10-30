package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.dsl.api.expression.DwExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TransformMapperTest {
    private val context = MappingContext(BabelfishRegistry(), Paths.get("test.xml"))
    private val location = SourceLocation(12, 8)

    @Test
    fun `translates XML payload resource into a generated resource assignment`(@TempDir directory: Path) {
        val xml = directory.resolve("resource-transform.xml")
        Files.writeString(
            xml,
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                  xmlns:ee="http://www.mulesoft.org/schema/mule/ee/core">
                <sub-flow name="convert">
                    <ee:transform>
                        <ee:message>
                            <ee:set-payload resource="dataweave/JAVA2JSON.dwl"/>
                        </ee:message>
                    </ee:transform>
                </sub-flow>
            </mule>
            """.trimIndent(),
        )
        val element = MuleXmlParser().parse(xml).subFlows.single().pipeline.single()

        val code = TransformCodeEmitter.emit(TransformMapper.map(element, context), CodegenContext("com.test"))

        assertTrue(code.contains("setPayload = DwExpression.Resource(\"dataweave/JAVA2JSON.dwl\")"), code)
    }

    @Test
    fun `maps resource expressions for payload attributes and variables`() {
        val config = TransformMapper.map(
            transform(
                expression("set-payload", resource = "dataweave/JAVA2JSON.dwl"),
                expression("set-attributes", resource = "dataweave/attributes.dwl"),
                expression("set-variable", resource = "dataweave/variable.dwl"),
            ),
            context,
        )

        assertEquals(DwExpression.Resource("dataweave/JAVA2JSON.dwl"), config.setPayload)
        assertEquals(DwExpression.Resource("dataweave/attributes.dwl"), config.setAttributes)
        assertEquals("result", config.setVariables.single().variableName)
        assertEquals(DwExpression.Resource("dataweave/variable.dwl"), config.setVariables.single().expression)

        val code = TransformCodeEmitter.emit(config, CodegenContext("com.test"))
        assertTrue(code.contains("setPayload = DwExpression.Resource(\"dataweave/JAVA2JSON.dwl\")"))
        assertTrue(code.contains("setAttributes = DwExpression.Resource(\"dataweave/attributes.dwl\")"))
        assertTrue(code.contains("setVariable(\"result\", DwExpression.Resource(\"dataweave/variable.dwl\"))"))
    }

    @Test
    fun `preserves inline expressions for payload attributes and variables`() {
        val script = "%dw 2.0\noutput application/json\n---\npayload"
        val config = TransformMapper.map(
            transform(
                expression("set-payload", inline = script),
                expression("set-attributes", inline = "attributes"),
                expression("set-variable", inline = "vars.input"),
            ),
            context,
        )

        assertEquals(DwExpression.Inline(script), config.setPayload)
        assertEquals(DwExpression.Inline("attributes"), config.setAttributes)
        assertEquals(DwExpression.Inline("vars.input"), config.setVariables.single().expression)
    }

    @Test
    fun `allows resource expressions with only formatting whitespace`() {
        val config = TransformMapper.map(
            transform(expression("set-payload", resource = "dataweave/script.dwl", inline = "\n  ")),
            context,
        )

        assertEquals(DwExpression.Resource("dataweave/script.dwl"), config.setPayload)
    }

    @Test
    fun `rejects missing blank or conflicting expression sources with child location`() {
        for (tag in listOf("set-payload", "set-attributes", "set-variable")) {
            val invalidExpressions = listOf(
                expression(tag),
                expression(tag, inline = " \n "),
                expression(tag, resource = ""),
                expression(tag, resource = " \t "),
                expression(tag, resource = "", inline = "payload"),
                expression(tag, resource = "dataweave/script.dwl", inline = "payload"),
            )
            for (invalid in invalidExpressions) {
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    TransformMapper.map(transform(invalid), context)
                }
                assertTrue(failure.message.orEmpty().contains(tag), failure.message)
                assertTrue(failure.message.orEmpty().contains("line 12, col 8"), failure.message)
            }
        }
    }

    @Test
    fun `keeps absent transform assignments optional`() {
        val config = TransformMapper.map(transform(), context)

        assertEquals(null, config.setPayload)
        assertEquals(null, config.setAttributes)
        assertTrue(config.setVariables.isEmpty())
    }

    private fun expression(tag: String, resource: String? = null, inline: String? = null): RawElement =
        RawElement(
            namespace = "ee",
            tag = tag,
            attrs = buildMap {
                if (resource != null) put("resource", resource)
                if (tag == "set-variable") put("variableName", "result")
            },
            children = emptyList(),
            location = location,
            textContent = inline,
        )

    private fun transform(vararg assignments: RawElement): RawElement =
        RawElement(
            namespace = "ee",
            tag = "transform",
            attrs = emptyMap(),
            children = listOf(
                RawElement(
                    namespace = "ee",
                    tag = "message",
                    attrs = emptyMap(),
                    children = assignments.filter { it.tag != "set-variable" },
                    location = SourceLocation(1, 1),
                ),
                RawElement(
                    namespace = "ee",
                    tag = "variables",
                    attrs = emptyMap(),
                    children = assignments.filter { it.tag == "set-variable" },
                    location = SourceLocation(1, 1),
                ),
            ),
            location = SourceLocation(1, 1),
        )
}
