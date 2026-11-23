package dev.museframework.babelfish.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("MuleXmlParser")
class MuleXmlParserTest {
    private val parser = MuleXmlParser()

    private fun fixture(name: String): Path = Path.of("src/test/resources/fixtures/$name")

    @Test
    fun `element leaf text preserves XML character references and boundary whitespace`(@TempDir directory: Path) {
        val file = directory.resolve("leaf.xml")
        Files.writeString(file, """<mule><sub-flow name="example"><value>  text&#13;&#10;&#13;&#10;</value></sub-flow></mule>""")
        val leaf = parser.parse(file).subFlows.single().pipeline.single()
        assertEquals("text", leaf.textContent)
        assertEquals("  text\r\n\r\n", leaf.untrimmedTextContent)
    }

    @Nested
    @DisplayName("line numbers")
    inner class LineNumbers {
        @Test
        fun `flow element has correct line number`() {
            val doc = parser.parse(fixture("simple-http-flow.xml"))
            assertTrue(
                doc.flows
                    .first()
                    .location.line > 0,
                "Flow should have line number > 0",
            )
        }

        @Test
        fun `pipeline elements have distinct line numbers`() {
            val doc = parser.parse(fixture("simple-http-flow.xml"))
            val lines =
                doc.flows
                    .first()
                    .pipeline
                    .map { it.location.line }
            assertTrue(lines.all { it > 0 }, "All pipeline elements should have line numbers > 0")
            assertEquals(lines.size, lines.distinct().size, "Line numbers should be distinct")
        }
    }

    @Nested
    @DisplayName("namespace resolution")
    inner class Namespaces {
        @Test
        fun `http listener has namespace http`() {
            val doc = parser.parse(fixture("simple-http-flow.xml"))
            val source = doc.flows.first().source
            assertNotNull(source)
            assertEquals("http", source.namespace)
            assertEquals("listener", source.tag)
        }

        @Test
        fun `core elements have empty namespace`() {
            val doc = parser.parse(fixture("simple-http-flow.xml"))
            val pipeline = doc.flows.first().pipeline
            val setVar = pipeline.first { it.tag == "set-variable" }
            assertEquals("", setVar.namespace)
        }
    }

    @Nested
    @DisplayName("structure")
    inner class Structure {
        @Test
        fun `sub-flow has no source`() {
            val doc = parser.parse(fixture("flow-with-subflow.xml"))
            assertEquals(1, doc.subFlows.size)
            assertEquals(null, doc.subFlows.first().source)
        }

        @Test
        fun `documentation metadata is read by namespace regardless of the XML prefix`() {
            val doc = parser.parse(fixture("doc-id-flow.xml"))
            val flow = doc.flows.single()
            val subFlow = doc.subFlows.single()

            assertEquals("flow-original", flow.docId)
            assertEquals("source-original", flow.source?.attrs?.get("doc:id"))
            assertEquals("subflow-original", subFlow.docId)
            assertEquals("processor-original", flow.pipeline.single().attrs["doc:id"])
        }

        @Test
        fun `global config IDs honor the documentation namespace without changing fallback allocation`() {
            val doc = parser.parse(fixture("config-doc-id-alias.xml"))
            val configs = doc.globalConfigs

            assertEquals(
                listOf(
                    "canonical-config-original",
                    "http-request-config-original",
                    "unnamed_config_1",
                    "unnamed_config_2",
                    "rebound-doc-alias",
                    "foreign-doc-prefix-only",
                ),
                configs.map { it.id },
            )
            assertEquals("canonical-config-original", configs[0].attrs["doc:id"])
            assertEquals("http-request-config-original", configs[1].attrs["meta:id"])
            assertEquals("http-request-config-original", configs[1].attrs["doc:id"])
            assertEquals("unnamed_config_1", configs[2].attrs["name"])
            assertEquals("HTTP request configuration", configs[2].attrs["doc:name"])
            assertEquals("foreign-id", configs[3].attrs["other:id"])
            assertEquals("documentation-id-original", configs[4].attrs["meta:id"])
            assertEquals("documentation-id-original", configs[4].attrs["doc:id"])
            assertEquals(null, configs[5].attrs["doc:id"])
            assertEquals(listOf(7, 8, 9, 10, 14, 17), configs.map { it.location.line })

            val flow = doc.flows.single()
            assertEquals("raw-config-flow-original", flow.docId)
            assertEquals("raw-config-element-original", flow.source?.attrs?.get("doc:id"))
            assertEquals(18, flow.location.line)
            assertEquals(19, flow.source?.location?.line)
        }

        @Test
        fun `error handler is separated from pipeline`() {
            val doc = parser.parse(fixture("flow-with-error-handler.xml"))
            val flow = doc.flows.first()
            assertNotNull(flow.errorHandler)
            assertTrue(flow.pipeline.none { it.tag == "error-handler" })
        }

        @Test
        fun `config-ref attribute is preserved on source`() {
            val doc = parser.parse(fixture("simple-http-flow.xml"))
            val source = doc.flows.first().source
            assertNotNull(source)
            // Verify config-ref matches the identifying name
            assertEquals("HTTP_Listener_config", source.attrs["config-ref"])

            assertEquals("Listener", source.attrs["doc:name"])
            assertEquals(null, source.attrs["name"])
        }
    }
}
