package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.config.SetPayloadConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("KotlinPoet code emitters")
class KotlinPoetCodeEmitterTest {
    @Test
    fun `set payload emits the complete DSL fragment and import`() {
        val context = CodegenContext("com.test")
        val config =
            SetPayloadConfig(
                docName = "test",
                docId = "123",
                value = "Hello World",
                mimeType = "text/plain",
                encoding = "UTF-8",
            )

        val code = KotlinPoetSetPayloadCodeEmitter.emit(config, context)

        assertEquals(
            """setPayload {
    value = "Hello World"
    mimeType = "text/plain"
    encoding = "UTF-8"
}""",
            code,
        )
        assertEquals(listOf("dev.museframework.dsl.dsl.setPayload"), context.resolvedImports())
    }

    @Test
    fun `choice emits the escaped Kotlin keyword used by the DSL`() {
        val context = CodegenContext("com.test")
        val config =
            ChoiceConfig(
                docName = "choice",
                docId = "456",
                whenRoutes =
                    listOf(
                        ChoiceRoute(
                            expression = "#[payload == \"A\"]",
                            pipeline = emptyList(),
                        ),
                    ),
                otherwise = null,
            )

        val code = KotlinPoetChoiceCodeEmitter.emit(config, context)

        assertEquals(
            """choice {
    `when`("#[payload == \"A\"]") {
    }
}""",
            code,
        )
        assertEquals(listOf("dev.museframework.dsl.dsl.choice"), context.resolvedImports())
    }
}
