package dev.museframework.plugin.core.check

import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.api.SourceMapper
import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.plugin.core.translation.TranslatorRegistry
import dev.museframework.dsl.api.model.component.Source
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuseCompatibilityCheckerTest {

    private val parser = MuleXmlParser()
    private val registry =
        TranslatorRegistry.load().createRegistry().apply {
            registerConfig("http", "listener-config", ConfigMapper { _, _ -> error("Mapping is not exercised") })
            registerSource("http", "listener", SourceMapper<Source> { _, _ -> error("Mapping is not exercised") })
        }

    @Test
    fun `all elements reported as implemented for known components`() {
        val doc = parser.parse(fixture("simple-flow.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertEquals(3, report.totalCount, "Expected 3 pipeline elements")
        assertEquals(0, report.unmappedCount, "All elements should be implemented")
        assertTrue(report.results.all { it.implemented })
    }

    @Test
    fun `unknown elements marked as not implemented`() {
        val doc = parser.parse(fixture("mixed-flow.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertEquals(3, report.totalCount, "Expected 3 pipeline elements")
        assertEquals(1, report.unmappedCount, "jms:publish should be unmapped")

        val jmsPublish = report.results.find { it.tag == "publish" && it.namespace == "jms" }
        assertTrue(jmsPublish != null, "Expected jms:publish in results")
        assertFalse(jmsPublish.implemented, "jms:publish should not be implemented")

        val setPayload = report.results.find { it.tag == "set-payload" }
        assertTrue(setPayload!!.implemented, "set-payload should be implemented")
    }

    @Test
    fun `nested router elements checked without structural false positives`() {
        val doc = parser.parse(fixture("nested-router.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertTrue(report.results.any { it.tag == "choice" }, "choice should be in results")
        assertTrue(report.results.any { it.tag == "logger" }, "logger should be in results")
        assertTrue(report.results.any { it.tag == "set-payload" }, "set-payload should be in results")
        assertTrue(report.results.any { it.tag == "set-variable" }, "set-variable should be in results")

        assertFalse(report.results.any { it.tag == "when" }, "when should NOT be in results")
        assertFalse(report.results.any { it.tag == "otherwise" }, "otherwise should NOT be in results")

        assertEquals(5, report.totalCount, "Expected choice + 4 nested processors (2 loggers, set-payload, set-variable)")
        assertEquals(0, report.unmappedCount, "All nested elements should be implemented")
    }

    @Test
    fun `error handler elements are not reported as false positives`() {
        val doc = parser.parse(fixture("error-handler-flow.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertTrue(report.results.any { it.tag == "set-payload" }, "set-payload should be in results")
        assertTrue(report.results.any { it.tag == "logger" }, "logger should be in results (in error handler)")

        assertFalse(report.results.any { it.tag == "error-handler" }, "error-handler should NOT be in results")
        assertFalse(report.results.any { it.tag == "on-error-continue" }, "on-error-continue should NOT be in results")

        assertEquals(2, report.totalCount, "Expected set-payload + logger in error handler")
        assertEquals(0, report.unmappedCount)
    }

    @Test
    fun `http components with config are all reported`() {
        val doc = parser.parse(fixture("simple-http-flow.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertTrue(report.results.any { it.tag == "listener-config" && it.type == ComponentType.CONFIG })
        assertTrue(report.results.any { it.tag == "listener" && it.type == ComponentType.SOURCE })
        assertTrue(report.results.any { it.tag == "set-payload" })
        assertTrue(report.results.any { it.tag == "logger" })

        assertTrue(report.results.all { it.implemented }, "All HTTP components should be implemented")
        assertEquals(4, report.totalCount)
    }

    @Test
    fun `known unimplemented elements are silently skipped`() {
        val doc = parser.parse(fixture("known-unimplemented.xml"))
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertTrue(report.results.any { it.tag == "set-payload" }, "set-payload should be in results")
        assertTrue(report.results.any { it.tag == "logger" }, "logger should be in results")

        assertFalse(report.results.any { it.tag == "console" }, "apikit:console should NOT be reported")

        assertEquals(2, report.totalCount, "Expected set-payload + logger only")
        assertEquals(0, report.unmappedCount)
    }

    @Test
    fun `multi-file scan produces combined report`() {
        val docs = listOf(
            parser.parse(fixture("simple-flow.xml")),
            parser.parse(fixture("mixed-flow.xml")),
        )
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(docs)

        assertEquals(2, report.fileReports.size)
        assertEquals(6, report.totalComponents, "3 + 3 elements across 2 files")
        assertEquals(1, report.totalUnmapped, "Only jms:publish is unmapped")
    }

    @Test

    fun `empty document produces empty report`() {
        val report = MuseCompatibilityChecker(registry).check(emptyList())
        assertTrue(report.fileReports.isEmpty())
        assertEquals(0, report.totalComponents)
        assertEquals(0, report.totalUnmapped)
        assertFalse(report.hasBlockingIssues())
    }

    @Test
    fun `scatter gather with routes handles nested elements`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xsi:schemaLocation="http://www.mulesoft.org/schema/mule/core
                                      http://www.mulesoft.org/schema/mule/core/current/mule.xsd">
                <flow name="sg-flow">
                    <scatter-gather>
                        <route>
                            <set-payload value="result1"/>
                        </route>
                        <route>
                            <logger level="INFO" message="result2"/>
                        </route>
                    </scatter-gather>
                </flow>
            </mule>
        """.trimIndent()
        val file = createTempXml(xml)
        val doc = parser.parse(file.toPath())
        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(doc)

        assertTrue(report.results.any { it.tag == "scatter-gather" })
        assertTrue(report.results.any { it.tag == "set-payload" })
        assertTrue(report.results.any { it.tag == "logger" })
        assertFalse(report.results.any { it.tag == "route" }, "route should NOT be in results")
        assertEquals(3, report.totalCount)
        assertEquals(0, report.unmappedCount)
    }

    private fun fixture(name: String): Path {
        val resource = javaClass.classLoader.getResource("fixtures/$name")
            ?: throw IllegalStateException("Fixture not found: $name")
        return Path.of(resource.toURI())
    }

    private fun createTempXml(content: String): java.io.File {
        val file = java.io.File.createTempFile("test-", ".xml")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }
}
