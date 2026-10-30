package dev.museframework.babelfish.parser

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("MuleXmlParser")
class MuleXmlParserTest {
    private val parser = MuleXmlParser()

    private fun fixture(name: String): Path = Path.of("src/test/resources/fixtures/$name")

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
