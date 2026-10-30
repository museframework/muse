package dev.museframework.babelfish.emitter

import dev.museframework.babelfish.mapper.TranslationResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun `imports are sorted and deduplicated`() {
        val emitter = KotlinCodeEmitter()
        val ctx = CodegenContext("com.acme")
        ctx.addImport("dev.museframework.dsl.dsl.setPayload")
        ctx.addImport("dev.museframework.dsl.dsl.setVariable")
        ctx.addImport("dev.museframework.dsl.dsl.setPayload") // duplicate

        val imports = ctx.resolvedImports()
        assertEquals(2, imports.size)
        assertEquals(imports.sorted(), imports)
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
