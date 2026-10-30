package dev.museframework.engine.dataweave

import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import java.util.Properties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataWeaveResourceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `Mule qualified property lookup shares the current property context with bare p`() {
        val properties = Properties().apply {
            setProperty("sftp.remote.export.path", "/upload")
            setProperty("visitors.export.filename", "test-output.txt")
        }
        val evaluator = MuseExpressionEvaluatorImpl(MuseDataWeaveExecutor(), properties)
        val expression = "#[Mule::p('sftp.remote.export.path') ++ '/' ++ p('visitors.export.filename')]"
        assertEquals("/upload/test-output.txt", evaluator.evaluate(expression, MuseEvent())?.value)
        properties.setProperty("sftp.remote.export.path", "/changed")
        assertEquals("/changed/test-output.txt", evaluator.evaluate(expression, MuseEvent())?.value)
    }

    @Test
    fun `classpath script preserves imports payload variables and correlation binding`() {
        val result = MuseDataWeaveExecutor().evaluateResource(
            "dataweave/ResourceTransform.dwl",
            DataWeaveContext(MuseEvent(
                payload = TypedValue.of(mapOf("value" to "input")),
                variables = mapOf("suffix" to TypedValue.of("tail")),
                correlationId = "resource-correlation",
            )),
        )
        assertEquals(mapOf("value" to "resource:input", "suffix" to "tail", "correlation" to "resource-correlation"), assertIs<Map<*, *>>(result?.value))
    }

    @Test
    fun `existing filesystem resource remains supported`() {
        val file = directory.resolve("script.dwl")
        file.writeText("%dw 2.0\noutput application/java\n---\npayload.value")
        val result = MuseDataWeaveExecutor().evaluateResource(file.toString(), DataWeaveContext(MuseEvent(payload = TypedValue.of(mapOf("value" to "file")))))
        assertEquals("file", result?.value)
    }
}
