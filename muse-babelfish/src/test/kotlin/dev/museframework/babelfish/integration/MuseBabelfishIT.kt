package dev.museframework.babelfish.integration

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.*
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Babelfish Integration")
class MuseBabelfishIT {

    private fun createTestRegistry(): BabelfishRegistry {
        return BabelfishRegistry().apply {
            // Register test mappers directly
            registerElement("", "set-payload", object : ElementMapper<SetPayloadConfig> {
                override fun map(element: RawElement, ctx: MappingContext): SetPayloadConfig {
                    return SetPayloadConfig(
                        docName = element.attrs["doc:name"] ?: "",
                        docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
                        value = element.attrs["value"] ?: "",
                        mimeType = element.attrs["mimeType"]
                    )
                }
            })
            registerElement("", "set-variable", object : ElementMapper<SetVariableConfig> {
                override fun map(element: RawElement, ctx: MappingContext): SetVariableConfig {
                    return SetVariableConfig(
                        docName = element.attrs["doc:name"] ?: "",
                        docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
                        variableName = element.attrs["variableName"] ?: "",
                        value = element.attrs["value"] ?: "",
                        mimeType = element.attrs["mimeType"]
                    )
                }
            })
            registerElement("", "logger", object : ElementMapper<LoggerConfig> {
                override fun map(element: RawElement, ctx: MappingContext): LoggerConfig {
                    return LoggerConfig(
                        docName = element.attrs["doc:name"] ?: "",
                        docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
                        level = LogLevel.INFO,
                        message = element.attrs["message"] ?: ""
                    )
                }
            })
        }
    }

    private fun createTestEmitter(): KotlinCodeEmitter {
        return KotlinCodeEmitter().apply {
            registerElementEmitter(SetPayloadConfig::class, object : ElementCodeEmitter<SetPayloadConfig> {
                override fun emit(element: SetPayloadConfig, ctx: CodegenContext): String {
                    ctx.addImport("dev.museframework.dsl.dsl.setPayload")
                    return """setPayload { value = "${element.value}" }"""
                }
            })
            registerElementEmitter(SetVariableConfig::class, object : ElementCodeEmitter<SetVariableConfig> {
                override fun emit(element: SetVariableConfig, ctx: CodegenContext): String {
                    ctx.addImport("dev.museframework.dsl.dsl.setVariable")
                    return """setVariable("${element.variableName}") { value = "${element.value}" }"""
                }
            })
            registerElementEmitter(LoggerConfig::class, object : ElementCodeEmitter<LoggerConfig> {
                override fun emit(element: LoggerConfig, ctx: CodegenContext): String {
                    ctx.addImport("dev.museframework.engine.components.processor.dsl.logger")
                    return """logger { message = "${element.message}" }"""
                }
            })
        }
    }

    @Test
    fun `translates simple flow with core elements`() {
        val registry = createTestRegistry()
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/fixtures/flow-with-subflow.xml"),
            registry
        )

        assertNotNull(result)
        assertEquals(2, result.flows.size, "Should have 2 flows (main + sub-flow)")

        val emitter = createTestEmitter()
        val code = emitter.emit(result, "com.test")

        assertContains(code, "package com.test")
        assertContains(code, "@MuseConfig")
        assertContains(code, "flow(\"main-flow\")")
        assertContains(code, "subFlow(\"process-order-sub-flow\")")
        assertContains(code, "setPayload")
        assertContains(code, "setVariable")
        assertContains(code, "logger")
    }

    @Test
    fun `preserves doc names and generates doc ids`() {
        val registry = createTestRegistry()
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/fixtures/flow-with-subflow.xml"),
            registry
        )

        val flow = result.flows.first()
        assertNotNull(flow.docId)
        assertTrue(flow.docId.isNotBlank())
    }

    @Test
    fun `maps error handlers with provider-neutral child mappers`() {
        val result =
            MuseBabelfish.translateXmlToMuseKotlinDsl(
                Path.of("src/test/resources/fixtures/flow-with-error-handler.xml"),
                createTestRegistry(),
            )

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        assertIs<SetPayloadConfig>(flow.pipeline.single())
        val strategy = assertIs<ErrorHandlerStrategy.OnErrorContinue>(flow.errorHandler?.strategies?.single())
        assertEquals("MULE", strategy.errorType.namespace)
        assertEquals("EXPRESSION", strategy.errorType.identifier)
        assertIs<LoggerConfig>(strategy.pipeline.single())
    }

    @Test
    fun `writeTo generates Kotlin file in output directory`() {
        val registry = createTestRegistry()
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/fixtures/flow-with-subflow.xml"),
            registry
        )

        val tempDir = java.nio.file.Files.createTempDirectory("babelfish-test")
        try {
            val writtenFiles = result.writeTo(tempDir, "com.test")

            assertEquals(1, writtenFiles.size, "Should write exactly one file")
            val outputFile = writtenFiles.first()
            assertTrue(java.nio.file.Files.exists(outputFile), "Output file should exist")
            assertEquals("flow-with-subflow.kt", outputFile.fileName.toString(), "File name should match")

            val content = java.nio.file.Files.readString(outputFile)
            assertContains(content, "package com.test")
            assertContains(content, "@MuseConfig")
            assertContains(content, "class FlowWithSubflowConfig")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writeTo preserves directory structure`() {
        val registry = createTestRegistry()
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(
            Path.of("src/test/resources/fixtures/flow-with-subflow.xml"),
            registry
        )

        val tempDir = java.nio.file.Files.createTempDirectory("babelfish-test")
        try {
            val nestedOutputDir = tempDir.resolve("src/main/resources/flows")
            val writtenFiles = result.writeTo(nestedOutputDir, "com.example")

            assertEquals(1, writtenFiles.size)
            val outputFile = writtenFiles.first()
            assertTrue(java.nio.file.Files.exists(outputFile), "Output file should exist in nested directory")
            assertEquals("flow-with-subflow.kt", outputFile.fileName.toString())

            val content = java.nio.file.Files.readString(outputFile)
            assertContains(content, "package com.example")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
