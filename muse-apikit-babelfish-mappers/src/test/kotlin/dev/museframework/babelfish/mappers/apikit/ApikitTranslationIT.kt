package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApikitTranslationIT {
    @Test
    fun `translates and emits APIKit config and router`() {
        val fixture = Path.of("src/test/resources/mule/apikit/apikit-router.xml")
        val provider = ApikitMapperProvider()
        val registry = BabelfishRegistry().apply { provider.registerMappings(this) }
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(fixture, registry)

        val config = assertIs<ApikitConfig>(result.usedConfigs.getValue("orders-api"))
        assertEquals("orders-api", config.name)
        assertEquals("Orders API", config.docName)
        assertEquals("apikit-config-001", config.docId)
        assertEquals("api/orders.raml", config.spec)
        assertEquals(fixture.toString(), config.sourceFile)

        val subFlow = assertIs<MuseFlow.SubFlow>(result.flows.single())
        val router = assertIs<ApikitRouter>(subFlow.pipeline.single())
        val reference = assertIs<ApikitRef.ByName>(router.configRef)
        assertEquals("orders-api", reference.name)
        assertEquals("Orders Router", router.docName)
        assertEquals("apikit-router-001", router.docId)

        val code =
            KotlinCodeEmitter()
                .apply { provider.registerEmitters(this) }
                .emit(result, "dev.museframework.babelfish.generated")

        assertContains(code, "import dev.museframework.apikit.api.dsl.apikit")
        assertContains(code, "import dev.museframework.apikit.api.dsl.apikitConfig")
        assertContains(code, "apikitConfig {")
        assertContains(code, "spec = \"api/orders.raml\"")
        assertContains(code, "apikit(\"orders-api\") {")
    }
}
