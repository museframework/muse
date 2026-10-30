package dev.museframework.engine.integration

import dev.museframework.dsl.api.config.CustomCodeConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.customCode
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.engine.api.event.MuseEvent
import dev.museframework.engine.api.event.TypedValue
import dev.museframework.engine.components.processor.customCodeProcessorBuilder
import dev.museframework.engine.components.processor.setPayloadProcessorBuilder
import dev.museframework.engine.factory.MuseComponentFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CustomCodeIT {
    @Test
    fun `should execute custom code in flow`() =
        runTest {
            val flow =
                flow("custom-code-flow") {
                    customCode {
                        docName = "Transform Data"
                        code { payload ->
                            val input = payload as? String ?: "default"
                            input.uppercase()
                        }
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("hello"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals("HELLO", result.payload?.value)
        }

    @Test
    fun `should compose custom code with setPayload`() =
        runTest {
            val flow =
                flow("custom-code-composition-flow") {
                    setPayload {
                        docName = "Set Initial Payload"
                        value = "10"
                    }
                    customCode {
                        docName = "Parse and Multiply"
                        code { payload ->
                            val number = (payload as? String)?.toIntOrNull() ?: 0
                            number * 5
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals(50, result.payload?.value)
        }

    @Test
    fun `should handle complex data transformation`() =
        runTest {
            data class User(
                val name: String,
                val age: Int,
            )

            data class UserDto(
                val fullName: String,
                val isAdult: Boolean,
            )

            val flow =
                flow("custom-code-mapping-flow") {
                    customCode {
                        docName = "Map User to DTO"
                        code { payload ->
                            val user = payload as? User ?: User("Unknown", 0)
                            UserDto(
                                fullName = user.name.uppercase(),
                                isAdult = user.age >= 18,
                            )
                        }
                    }
                }
            val input = MuseEvent(payload = TypedValue.of(User("Alice", 20)))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            val dto = assertIs<UserDto>(result.payload?.value)
            assertEquals("ALICE", dto.fullName)
            assertEquals(true, dto.isAdult)
        }

    @Test
    fun `should handle list processing`() =
        runTest {
            val flow =
                flow("custom-code-list-flow") {
                    customCode {
                        docName = "Filter and Transform List"
                        code { payload ->
                            val list = payload as? List<*> ?: emptyList<Int>()
                            list
                                .filterIsInstance<Int>()
                                .filter { it > 5 }
                                .sumOf { it * 2 }
                        }
                    }
                }
            val input = MuseEvent(payload = TypedValue.of(listOf(1, 6, "ignored", 8)))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals(28, result.payload?.value)
        }

    @Test
    fun `should handle error with handleErrors enabled`() =
        runTest {
            val flow =
                flow("custom-code-error-flow") {
                    customCode {
                        docName = "Throw Error"
                        handleErrors = true
                        code { _ -> throw IllegalStateException("Test error") }
                    }
                    errorHandler {
                        onErrorContinue(ErrorType.Mule.UNKNOWN) {
                            add(
                                SetPayloadConfig(
                                    docName = "Capture Error Description",
                                    docId = "capture-cc-error",
                                    value = "#[error.description]",
                                ),
                            )
                        }
                    }
                }

            val result = EngineFlowHarness(this, flow, factory()).execute()

            assertEquals("Custom code execution failed: Test error", result.payload?.value)
            assertNull(result.error)
        }

    @Test
    fun `should chain multiple custom code processors`() =
        runTest {
            val flow =
                flow("custom-code-chain-flow") {
                    customCode {
                        docName = "Step 1: Parse"
                        code { payload -> (payload as? String)?.toIntOrNull() ?: 0 }
                    }
                    customCode {
                        docName = "Step 2: Multiply"
                        code { payload -> (payload as? Int ?: 0) * 3 }
                    }
                    customCode {
                        docName = "Step 3: Format"
                        code { payload -> "Result: $payload" }
                    }
                }
            val input = MuseEvent(payload = TypedValue.of("4"))

            val result = EngineFlowHarness(this, flow, factory()).execute(input)

            assertEquals("Result: 12", result.payload?.value)
        }

    private fun factory(): MuseComponentFactory =
        MuseComponentFactory().apply {
            registerProcessor(CustomCodeConfig::class, customCodeProcessorBuilder)
            registerProcessor(SetPayloadConfig::class, setPayloadProcessorBuilder)
        }
}
