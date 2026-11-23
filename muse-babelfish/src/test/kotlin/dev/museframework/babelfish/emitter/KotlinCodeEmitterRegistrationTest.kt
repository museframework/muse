package dev.museframework.babelfish.emitter

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.error.ErrorHandlerStrategy
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.babelfish.exception.BabelfishRegistrationCollisionException
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.spi.BabelfishRegistrationOwner
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KotlinCodeEmitterRegistrationTest {

    @Test
    fun `element emitter collision identifies both owners and retains the first emitter`() {
        val emitter = KotlinCodeEmitter()
        emitter.withRegistrationOwner(ALPHA_OWNER) {
            emitter.registerElementEmitter(SharedModel::class, FirstElementEmitter)
        }

        val exception =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                emitter.withRegistrationOwner(BETA_OWNER) {
                    emitter.registerElementEmitter(SharedModel::class, SecondElementEmitter)
                }
            }

        assertEquals("element emitter", exception.category)
        assertEquals(SharedModel::class.java.name, exception.key)
        assertEquals(ALPHA_OWNER, exception.existingOwner)
        assertEquals(BETA_OWNER, exception.incomingOwner)
        assertContains(exception.message!!, FirstElementEmitter::class.java.name)
        assertContains(exception.message!!, SecondElementEmitter::class.java.name)

        val code =
            emitter.emit(
                TranslationResult(
                    sourceFile = Path.of("flow.xml"),
                    flows =
                        listOf(
                            MuseFlow.SubFlow(
                                name = "flow",
                                docId = "flow-id",
                                pipeline = listOf(SharedModel()),
                            ),
                        ),
                    usedConfigs = emptyMap(),
                ),
                packageName = "com.example",
            )
        assertContains(code, "firstEmitter()")
        assertFalse(code.contains("secondEmitter()"))
    }

    @Test
    fun `source and configuration emitter collisions use category-specific keys`() {
        val sourceEmitter = KotlinCodeEmitter()
        sourceEmitter.withRegistrationOwner(ALPHA_OWNER) {
            sourceEmitter.registerSourceEmitter(SharedModel::class, FirstSourceEmitter)
        }
        val sourceException =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                sourceEmitter.withRegistrationOwner(BETA_OWNER) {
                    sourceEmitter.registerSourceEmitter(SharedModel::class, SecondSourceEmitter)
                }
            }
        assertEquals("source emitter", sourceException.category)

        val configEmitter = KotlinCodeEmitter()
        configEmitter.withRegistrationOwner(ALPHA_OWNER) {
            configEmitter.registerConfigEmitter(SharedModel::class, FirstConfigEmitter)
        }
        val configException =
            assertFailsWith<BabelfishRegistrationCollisionException> {
                configEmitter.withRegistrationOwner(BETA_OWNER) {
                    configEmitter.registerConfigEmitter(SharedModel::class, SecondConfigEmitter)
                }
            }
        assertEquals("configuration emitter", configException.category)
        assertEquals(FirstConfigEmitter::class.java.name, configException.existingRegistrationClass)
        assertEquals(SecondConfigEmitter::class.java.name, configException.incomingRegistrationClass)
    }

    @Test
    fun `same model class in different emitter categories does not collide`() {
        val emitter = KotlinCodeEmitter()

        emitter.withRegistrationOwner(ALPHA_OWNER) {
            emitter.registerElementEmitter(SharedModel::class, FirstElementEmitter)
            emitter.registerSourceEmitter(SharedModel::class, FirstSourceEmitter)
            emitter.registerConfigEmitter(SharedModel::class, FirstConfigEmitter)
        }
    }

    @Test
    fun `missing registered model categories identify the XML and enclosing flow`() {
        val missing = SharedModel()
        val source = Path.of("inbound/order.xml")
        val emitter = KotlinCodeEmitter()
        val handler = ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(
            errorType = ErrorType.Mule.ANY, pipeline = listOf(missing),
        )))
        val result = TranslationResult(source, emptyList(), emptyMap())
        val cases = listOf(
            "configuration" to result.copy(usedConfigs = mapOf("config" to missing)),
            "source" to result.copy(flows = listOf(MuseFlow.Flow("orders", "id", source = missing))),
            "element" to result.copy(flows = listOf(MuseFlow.Flow("orders", "id", errorHandler = handler))),
        )
        for ((category, model) in cases) {
            val message = assertFailsWith<IllegalStateException> { emitter.emit(model, "app") }.message!!
            assertContains(message, "$category emitter")
            assertContains(message, source.toString())
            assertContains(message, SharedModel::class.java.name)
            if (model.flows.isNotEmpty()) assertContains(message, "flow 'orders'")
        }
    }

    @Test
    fun `native provider preserves distinct config and flow names and imports from an error strategy child`() {
        val emitter = KotlinCodeEmitter().apply {
            registerConfigEmitter(SharedModel::class, FirstConfigEmitter)
            registerElementEmitter(SharedModel::class) { _, _ ->
                CodeBlock.of("%M()", MemberName("example.provider", "nestedCall", isExtension = true))
            }
        }
        val config = SharedModel()
        val handler = ErrorHandler(listOf(ErrorHandlerStrategy.OnErrorContinue(
            errorType = ErrorType.Mule.ANY, pipeline = listOf(config), whenExpression = "#[ok]",
            logException = false, enableNotifications = false,
        )))
        val result = TranslationResult(Path.of("order.xml"),
            listOf(MuseFlow.Flow("http-id", "flow-id", errorHandler = handler)),
            mapOf("http_id" to config))
        val declaration = ConfigurationClassSpec.Declaration(emptyList(), "example.Provider")
        val file = emitter.emitFile(result, "com.example", true, ConfigurationClassSpec(declaration, declaration))
        val type = file.typeSpecs.single()
        assertEquals(listOf("httpId", "httpId_"), type.propertySpecs.map { it.name })
        val code = file.toString()
        assertContains(code, "import example.provider.nestedCall")
        assertContains(code, "flow(\"http-id\")")
        assertContains(code, "logException = false")
        assertContains(code, "enableNotifications = false")
        assertContains(code, "whenExpression = \"#[ok]\"")
        assertContains(code, "listOf(httpId_)")
        assertContains(code, "listOf(httpId)")
    }

    private companion object {
        val ALPHA_OWNER = BabelfishRegistrationOwner("alpha", "example.AlphaProvider")
        val BETA_OWNER = BabelfishRegistrationOwner("beta", "example.BetaProvider")
    }

    private class SharedModel : PipelineElement, Source, MuseConfiguration {
        override val docName: String = "shared"
        override val docId: String = "shared-id"
        override val docDescription: String? = null
        override val sourceFile: String? = null
        override val sourceLine: Int? = null
        override val sourceClass: String? = null
    }

    private object FirstElementEmitter : ElementCodeEmitter<SharedModel> {
        override fun emit(element: SharedModel, ctx: CodegenContext) = CodeBlock.of("firstEmitter()")
    }

    private object SecondElementEmitter : ElementCodeEmitter<SharedModel> {
        override fun emit(element: SharedModel, ctx: CodegenContext) = CodeBlock.of("secondEmitter()")
    }

    private object FirstSourceEmitter : SourceCodeEmitter<SharedModel> {
        override fun emit(source: SharedModel, ctx: CodegenContext) = CodeBlock.of("firstSource()")
    }

    private object SecondSourceEmitter : SourceCodeEmitter<SharedModel> {
        override fun emit(source: SharedModel, ctx: CodegenContext) = CodeBlock.of("secondSource()")
    }

    private object FirstConfigEmitter : ConfigCodeEmitter<SharedModel> {
        override fun emit(config: SharedModel, ctx: CodegenContext) = CodeBlock.of("firstConfig()")
    }

    private object SecondConfigEmitter : ConfigCodeEmitter<SharedModel> {
        override fun emit(config: SharedModel, ctx: CodegenContext) = CodeBlock.of("secondConfig()")
    }
}
