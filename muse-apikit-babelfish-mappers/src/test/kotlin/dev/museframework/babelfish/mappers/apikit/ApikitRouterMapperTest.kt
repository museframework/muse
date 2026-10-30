package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.MappingAttributeException
import dev.museframework.babelfish.model.RawElement
import dev.museframework.babelfish.model.SourceLocation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ApikitRouterMapperTest {
    @Test
    fun `preserves named config reference and documentation metadata`() {
        val router =
            ApikitRouterMapper.map(
                rawRouter(
                    mapOf(
                        "config-ref" to "orders-api",
                        "doc:name" to "Orders Router",
                        "doc:id" to "apikit-router-001",
                    ),
                ),
                mappingContext(),
            )

        val reference = assertIs<ApikitRef.ByName>(router.configRef)
        assertEquals("orders-api", reference.name)
        assertEquals("Orders Router", router.docName)
        assertEquals("apikit-router-001", router.docId)
    }

    @Test
    fun `rejects router without config reference`() {
        val exception =
            assertFailsWith<MappingAttributeException> {
                ApikitRouterMapper.map(rawRouter(emptyMap()), mappingContext())
            }

        assertContains(exception.message.orEmpty(), "config-ref")
    }

    @Test
    fun `emits APIKit router DSL and public API import`() {
        val router =
            ApikitRouterMapper.map(
                rawRouter(
                    mapOf(
                        "config-ref" to "orders-api",
                        "doc:name" to "Orders Router",
                        "doc:id" to "apikit-router-001",
                    ),
                ),
                mappingContext(),
            )
        val context = CodegenContext("com.example")

        val code = ApikitRouterCodeEmitter.emit(router, context)

        assertContains(code, "apikit(\"orders-api\") {")
        assertContains(code, "docName = \"Orders Router\"")
        assertEquals(listOf("dev.museframework.apikit.api.dsl.apikit"), context.resolvedImports())
    }

    private fun rawRouter(attrs: Map<String, String>): RawElement =
        RawElement(
            namespace = "apikit",
            tag = "router",
            attrs = attrs,
            children = emptyList(),
            location = SourceLocation(10, 5),
        )

    private fun mappingContext(): MappingContext = MappingContext(BabelfishRegistry(), Path.of("test.xml"))
}
