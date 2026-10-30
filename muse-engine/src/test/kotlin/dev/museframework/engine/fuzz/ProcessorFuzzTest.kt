package dev.museframework.engine.fuzz

import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.SetPayloadMuseProcessor
import dev.museframework.engine.components.processor.SetVariableMuseProcessor
import dev.museframework.engine.components.processor.TransformMuseProcessor
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.ProcessorResult
import dev.museframework.engine.expression.MuseExpressionEvaluatorImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

@DisplayName("ProcessorFuzzTest")
class ProcessorFuzzTest {
    @Nested
    @DisplayName("MuseEvent creation")
    inner class MuseEventCreation {
        private fun generateRandomTypedValue(random: Random): TypedValue<Any> {
            val options =
                listOf(
                    { TypedValue.of(randomString(random, 0, 10000) as Any) },
                    { TypedValue.of(randomByteArray(random) as Any) },
                    { TypedValue.empty<Any>() },
                    { TypedValue.of(randomString(random, 1, 1000) as Any, TypedValue.MIME_JSON) },
                    { TypedValue.of("" as Any) },
                    { TypedValue.of(buildString { repeat(100) { append(random.nextInt().toChar()) } } as Any) },
                    { TypedValue.of("A".repeat(100000) as Any) },
                    { TypedValue.of("\uD83D\uDE00\uD83C\uDF89\uD83D\uDE80" as Any) },
                )
            return options.random(random)()
        }

        private fun randomString(
            random: Random,
            minSize: Int,
            maxSize: Int,
        ): String {
            val size = random.nextInt(minSize, maxSize + 1)
            val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + " \t\n\r"
            return buildString { repeat(size) { append(chars.random(random)) } }
        }

        private fun randomByteArray(random: Random): ByteArray {
            val size = random.nextInt(0, 10001)
            return ByteArray(size) { random.nextInt().toByte() }
        }

        @Test
        fun `should handle random payloads`() {
            val iterations = 100
            val random = Random(42L)
            var successCount = 0

            repeat(iterations) {
                val payload = generateRandomTypedValue(random)

                val event =
                    MuseEvent(
                        payload = payload,
                        attributes = null,
                        variables = emptyMap(),
                    )

                assertTrue(event.correlationId.isNotEmpty()) {
                    "correlationId should be generated"
                }
                assertTrue(event.payload == payload) {
                    "payload should match input"
                }
                successCount++
            }

            assertTrue(successCount == iterations) {
                "All $iterations iterations should succeed, got $successCount"
            }
        }

        @Test
        fun `should handle random payloads with attributes`() {
            val iterations = 100
            val random = Random(42L)
            var successCount = 0

            repeat(iterations) {
                val payload = generateRandomTypedValue(random)
                val attributes = generateRandomTypedValue(random)

                val event =
                    MuseEvent(
                        payload = payload,
                        attributes = attributes,
                        variables = emptyMap(),
                    )

                assertTrue(event.correlationId.isNotEmpty()) {
                    "correlationId should be generated"
                }
                assertTrue(event.payload == payload) {
                    "payload should match input"
                }
                assertTrue(event.attributes == attributes) {
                    "attributes should match input"
                }
                successCount++
            }

            assertTrue(successCount == iterations) {
                "All $iterations iterations should succeed, got $successCount"
            }
        }

        @Test
        fun `should handle random payloads with variables`() {
            val iterations = 100
            val random = Random(42L)
            var successCount = 0

            repeat(iterations) {
                val payload = generateRandomTypedValue(random)

                val variables =
                    mapOf(
                        "var1" to payload,
                        "var2" to TypedValue.of("test" as Any),
                        "var3" to null,
                    )

                val event =
                    MuseEvent(
                        payload = payload,
                        variables = variables,
                    )

                assertTrue(event.correlationId.isNotEmpty()) {
                    "correlationId should be generated"
                }
                assertTrue(event.variables == variables) {
                    "variables should match input"
                }
                successCount++
            }

            assertTrue(successCount == iterations) {
                "All $iterations iterations should succeed, got $successCount"
            }
        }
    }

    @Nested
    @DisplayName("Processor chains")
    inner class ProcessorChains {
        private val expressionEvaluator = mockk<MuseExpressionEvaluatorImpl>()

        private fun generateRandomTypedValue(random: Random): TypedValue<Any> {
            val options =
                listOf(
                    { TypedValue.of(randomString(random, 0, 10000) as Any) },
                    { TypedValue.of(randomByteArray(random) as Any) },
                    { TypedValue.empty<Any>() },
                    { TypedValue.of(randomString(random, 1, 1000) as Any, TypedValue.MIME_JSON) },
                    { TypedValue.of("" as Any) },
                    { TypedValue.of(buildString { repeat(100) { append(random.nextInt().toChar()) } } as Any) },
                    { TypedValue.of("A".repeat(100000) as Any) },
                    { TypedValue.of("\uD83D\uDE00\uD83C\uDF89\uD83D\uDE80" as Any) },
                )
            return options.random(random)()
        }

        private fun randomString(
            random: Random,
            minSize: Int,
            maxSize: Int,
        ): String {
            val size = random.nextInt(minSize, maxSize + 1)
            val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + " \t\n\r"
            return buildString { repeat(size) { append(chars.random(random)) } }
        }

        private fun randomByteArray(random: Random): ByteArray {
            val size = random.nextInt(0, 10001)
            return ByteArray(size) { random.nextInt().toByte() }
        }

        @Test
        fun `should handle processor chains`() =
            runBlocking {
                val iterations = 100
                val random = Random(42L)
                val scope = CoroutineScope(Dispatchers.Unconfined)
                var successCount = 0

                repeat(iterations) {
                    val initialPayload = generateRandomTypedValue(random)
                    val initialEvent = MuseEvent(payload = initialPayload)
                    val ctx =
                        ExecutionContext(
                            event = initialEvent,
                            errorHandler = null,
                            coroutineScope = scope,
                            expressionEvaluator = expressionEvaluator,
                        )

                    val setPayloadConfig =
                        SetPayloadConfig(
                            docName = "setPayload",
                            docId = "setPayload-1",
                            value = "fuzzed-payload",
                        )
                    val setPayloadProcessor = SetPayloadMuseProcessor(setPayloadConfig, expressionEvaluator)
                    every { expressionEvaluator.evaluate("fuzzed-payload", any()) } returns
                        TypedValue.of("fuzzed-payload", TypedValue.MIME_TEXT)

                    val result1 = setPayloadProcessor.process(ctx)

                    assertTrue(result1 is ProcessorResult.Success || result1 is ProcessorResult.Failure) {
                        "setPayload must return Success or Failure per ADR-001, got: ${result1::class.simpleName}"
                    }

                    if (result1 is ProcessorResult.Success) {
                        val ctx2 = result1.context

                        val setVariableConfig =
                            SetVariableConfig(
                                docName = "setVariable",
                                docId = "setVariable-1",
                                variableName = "fuzzedVar",
                                value = "fuzzed-value",
                            )
                        val setVariableProcessor = SetVariableMuseProcessor(setVariableConfig, expressionEvaluator)
                        every { expressionEvaluator.evaluate("fuzzed-value", any()) } returns
                            TypedValue.of("fuzzed-value", TypedValue.MIME_TEXT)

                        val result2 = setVariableProcessor.process(ctx2)

                        assertTrue(result2 is ProcessorResult.Success || result2 is ProcessorResult.Failure) {
                            "setVariable must return Success or Failure per ADR-001, got: ${result2::class.simpleName}"
                        }

                        if (result2 is ProcessorResult.Success) {
                            val ctx3 = result2.context

                            val transformConfig =
                                TransformConfig(
                                    docName = "transform",
                                    docId = "transform-1",
                                    setPayload = DwExpression.Inline("upper(payload)"),
                                )
                            val transformProcessor = TransformMuseProcessor(transformConfig, mockk(relaxed = true))

                            val result3 = transformProcessor.process(ctx3)

                            assertTrue(result3 is ProcessorResult.Success || result3 is ProcessorResult.Failure) {
                                "transform must return Success or Failure per ADR-001, got: ${result3::class.simpleName}"
                            }
                        }
                    }
                    successCount++
                }

                assertTrue(successCount == iterations) {
                    "All $iterations processor chain iterations should complete without throwing"
                }
            }
    }
}
