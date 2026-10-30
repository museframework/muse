package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.dsl.setVariable
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.components.processor.setVariableProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SetPayloadIT {
    @Test
    fun `should set payload with literal value`() =
        runTest {
            val flow =
                flow("set-payload-literal-flow") {
                    setPayload {
                        docName = "Set Payload"
                        value = "new-payload-value"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("new-payload-value", result.payload?.value)
        }

    @Test
    fun `should set payload with expression value`() =
        runTest {
            val flow =
                flow("set-payload-expr-flow") {
                    setPayload {
                        docName = "Set Payload Expression"
                        value = "#[payload]"
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("original-payload"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals("original-payload", result.payload?.value)
        }

    @Test
    fun `should compose setPayload with setVariable`() =
        runTest {
            val flow =
                flow("set-payload-and-variable-flow") {
                    setPayload {
                        docName = "Set Payload"
                        value = "custom-payload"
                    }
                    setVariable("status") {
                        docName = "Set Status"
                        value = "200"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("custom-payload", result.payload?.value)
            assertEquals("200", result.variables["status"]?.value)
        }

    @Test
    fun `should apply mimeType to literal payload`() =
        runTest {
            val flow =
                flow("set-payload-mime-literal-flow") {
                    setPayload {
                        docName = "Set Payload JSON"
                        value = """{"key": "value"}"""
                        mimeType = TypedValue.MIME_JSON
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("""{"key": "value"}""", result.payload?.value)
            assertEquals(TypedValue.MIME_JSON, result.payload?.mimeType)
        }

    @Test
    fun `should apply mimeType to expression payload`() =
        runTest {
            val flow =
                flow("set-payload-mime-expr-flow") {
                    setPayload {
                        docName = "Set Payload XML Expression"
                        value = "#[payload]"
                        mimeType = TypedValue.MIME_XML
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("expression-payload"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals("expression-payload", result.payload?.value)
            assertEquals(TypedValue.MIME_XML, result.payload?.mimeType)
        }

    @Test
    fun `should preserve default mimeType when mimeType not configured`() =
        runTest {
            val flow =
                flow("set-payload-no-mime-flow") {
                    setPayload {
                        docName = "Set Payload No Mime"
                        value = "plain text content"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("plain text content", result.payload?.value)
            assertEquals(TypedValue.MIME_TEXT, result.payload?.mimeType)
        }

    @Test
    fun `should set payload to null when expression returns null with mimeType configured`() =
        runTest {
            val flow =
                flow("set-payload-null-mime-flow") {
                    setPayload {
                        docName = "Set Payload Null Expr"
                        value = "#[null]"
                        mimeType = TypedValue.MIME_JSON
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            val payload = assertNotNull(result.payload)
            assertNull(payload.value)
            assertEquals(TypedValue.MIME_JSON, payload.mimeType)
        }

    @Test
    fun `should propagate mimeType through setPayload into setVariable reading payload`() =
        runTest {
            val flow =
                flow("set-payload-mime-chain-flow") {
                    setPayload {
                        docName = "Set Payload Custom Mime"
                        value = "some-data"
                        mimeType = TypedValue.MIME_OCTET_STREAM
                    }
                    setVariable("data") {
                        docName = "Capture Into Variable"
                        value = "#[payload]"
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(TypedValue.MIME_OCTET_STREAM, result.payload?.mimeType)
            assertEquals("some-data", result.payload?.value)
            assertEquals("some-data", result.variables["data"]?.value?.toString())
        }

    @Test
    fun `should override previous mimeType when second setPayload specifies different mimeType`() =
        runTest {
            val flow =
                flow("set-payload-mime-override-flow") {
                    setPayload {
                        docName = "Set Payload JSON"
                        value = """{"status": "ok"}"""
                        mimeType = TypedValue.MIME_JSON
                    }
                    setPayload {
                        docName = "Override to XML"
                        value = "<status>ok</status>"
                        mimeType = TypedValue.MIME_XML
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("<status>ok</status>", result.payload?.value)
            assertEquals(TypedValue.MIME_XML, result.payload?.mimeType)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
            registerProcessor(SetVariableConfig::class, setVariableProcessorBuilder)
        }
}
