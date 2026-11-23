package dev.museframework.babelfish.emitter

import com.squareup.kotlinpoet.CodeBlock
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path

@DisplayName("KotlinCodeEmitter")
class KotlinCodeEmitterTest {

    @Test
    fun `emits package and class declaration`() {
        val emitter = KotlinCodeEmitter()
        val result = TranslationResult(
            sourceFile = Path.of("order-flow.xml"),
            flows = emptyList(),
            usedConfigs = emptyMap()
        )
        val code = emitter.emit(result, "com.acme.flows")

        assertContains(code, "package com.acme.flows")
        assertContains(code, "@MuseConfig")
        assertContains(code, "class OrderFlowConfig : MuseConfigDefinition()")
    }

    @Test
    fun `uses runtime annotations supplied by configuration spec`() {
        val result =
            TranslationResult(
                sourceFile = Path.of("quarkus-flow.xml"),
                flows = emptyList(),
                usedConfigs = emptyMap(),
            )
        val spec =
            ConfigurationClassSpec(
                definition =
                    ConfigurationClassSpec.Declaration(
                        annotationTypes =
                            listOf(
                                "dev.museframework.dsl.annotation.MuseConfig",
                                "jakarta.enterprise.context.ApplicationScoped",
                            ),
                    ),
            )

        val code =
            KotlinCodeEmitter().emit(
                result = result,
                packageName = "com.acme",
                configurationClassSpec = spec,
            )

        assertContains(code, "import jakarta.enterprise.context.ApplicationScoped")
        assertContains(code, "@ApplicationScoped")
        assertContains(code, "class QuarkusFlowConfig : MuseConfigDefinition()")
    }

    @Test
    fun `uses runtime provider interface for native generation`() {
        val result =
            TranslationResult(
                sourceFile = Path.of("native-flow.xml"),
                flows = emptyList(),
                usedConfigs = emptyMap(),
            )
        val spec =
            ConfigurationClassSpec(
                definition =
                    ConfigurationClassSpec.Declaration(
                        annotationTypes = listOf("dev.museframework.dsl.annotation.MuseConfig"),
                    ),
                native =
                    ConfigurationClassSpec.Declaration(
                        annotationTypes = listOf("jakarta.enterprise.context.ApplicationScoped"),
                        providerInterface = "dev.museframework.quarkus.MuseConfigProvider",
                    ),
            )

        val code =
            KotlinCodeEmitter().emit(
                result = result,
                packageName = "com.acme",
                nativeBuild = true,
                configurationClassSpec = spec,
            )

        assertContains(code, "import dev.museframework.quarkus.MuseConfigProvider")
        assertContains(code, "class NativeFlowConfig : MuseConfigProvider")
        assertTrue(!code.contains("MuseConfigDefinition"))
    }

    @Test
    fun `escapes flow and subflow documentation IDs in generated Kotlin`() {
        val result = TranslationResult(
            sourceFile = Path.of("escaped-flow.xml"),
            flows = listOf(
                MuseFlow.Flow(name = "flow", docId = "flow-\"quoted\nline"),
                MuseFlow.SubFlow(name = "sub", docId = "sub-\"quoted\r\tend"),
            ),
            usedConfigs = emptyMap(),
        )

        val code = KotlinCodeEmitter().emit(result, "com.acme")

        assertContains(code, "\"flow-\\\"quoted\"")
        assertContains(code, "\"line\"")
        assertContains(code, "docId = \"sub-\\\"quoted\\r\\tend\"")
    }

    @Test
    fun `class name from file name`() {
        val emitter = KotlinCodeEmitter()
        val result = TranslationResult(
            sourceFile = Path.of("customer-data-flow.xml"),
            flows = emptyList(),
            usedConfigs = emptyMap()
        )
        val code = emitter.emit(result, "com.acme")
        assertContains(code, "class CustomerDataFlowConfig")
    }

    @Test
    fun `preserves internal uppercase letters in generated class names`() {
        val result = TranslationResult(Path.of("orderAPI.xml"), emptyList(), emptyMap())

        val code = KotlinCodeEmitter().emit(result, "com.example")

        assertContains(code, "class OrderAPIConfig")
    }

    @Test
    fun `config emitter declares its public generated type independently of the mapped model`() {
        val emitter = KotlinCodeEmitter()
        emitter.registerConfigEmitter(
            PrivateProviderConfig::class,
            PublicProviderConfig::class,
            ConfigCodeEmitter { _, _ -> CodeBlock.of("%T()", PublicProviderConfig::class) },
        )
        val result =
            TranslationResult(
                sourceFile = Path.of("provider-config.xml"),
                flows = emptyList(),
                usedConfigs = mapOf("provider-config" to PrivateProviderConfig()),
            )

        val code = emitter.emit(result, "com.example")

        assertContains(code, "val providerConfig: PublicProviderConfig = PublicProviderConfig()")
        assertFalse(code.contains("PrivateProviderConfig"))
    }

    @Test
    fun `class and property names have one stable allocation per file`() {
        val result = TranslationResult(
            Path.of("3-order.API__flow.XML"),
            listOf("a-b", "a_b", "when", "99", "", "---").map { MuseFlow.SubFlow(it, it) },
            emptyMap(),
        )
        val emitter = KotlinCodeEmitter()
        val file = emitter.emitFile(result, "com.acme")
        assertEquals("_3OrderAPIFlowConfig", file.name)
        assertEquals(listOf("aB", "aB_", "when_", "_99", "flow", "flow_"),
            file.typeSpecs.single().propertySpecs.map { it.name })
        assertContains(file.toString(), "subFlow(\"a_b\")")
        assertEquals(file.toString(), emitter.emit(result, "com.acme"))
    }

    @Test
    fun `keyword and punctuation filenames have stable legal class names`() {
        val emitter = KotlinCodeEmitter()
        for ((input, expected) in mapOf("when.xml" to "WhenConfig", "---.xml" to "Config", "99.xml" to "_99Config")) {
            val file = emitter.emitFile(TranslationResult(Path.of(input), emptyList(), emptyMap()), "com.example")
            assertEquals(expected, file.name)
            assertEquals(expected, file.typeSpecs.single().name)
        }
    }

    @Test
    fun `config val name normalization`() {
        val ctx = CodegenContext("com.acme")
        assertEquals("httpListenerConfig", ctx.configValName("HTTP_Listener_config"))
        assertEquals("dbConfig", ctx.configValName("DB_Config"))
    }

    @Test
    fun `flow val name normalization`() {
        val ctx = CodegenContext("com.acme")
        assertEquals("orderApiFlow", ctx.flowValName("order-api-flow"))
        assertEquals("processOrder", ctx.flowValName("process_order"))
    }
}

private class PrivateProviderConfig : MuseConfiguration {
    override val docName: String = "private"
    override val docId: String = "private"
    override val docDescription: String? = null
    override val sourceFile: String? = null
    override val sourceLine: Int? = null
    override val sourceClass: String? = null
}

class PublicProviderConfig : MuseConfiguration {
    override val docName: String = "public"
    override val docId: String = "public"
    override val docDescription: String? = null
    override val sourceFile: String? = null
    override val sourceLine: Int? = null
    override val sourceClass: String? = null
}
