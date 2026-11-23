package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.babelfish.spi.BabelfishMapperProvider
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.SchedulerConfig
import dev.museframework.dsl.api.config.SetPayloadConfig
import dev.museframework.dsl.api.model.flow.MuseFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoreMapperProviderTest {
    @Test
    fun `composes a scheduler and choice with an imported payload extension`(@TempDir dir: Path) {
        val path = dir.resolve("pilot.xml")
        path.toFile().writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core"
                  xmlns:doc="http://www.mulesoft.org/schema/mule/documentation">
              <flow name="pilot" doc:id="flow">
                <scheduler doc:id="source"/>
                <choice doc:id="router">
                  <when expression="#[true]">
                    <set-payload doc:id="leaf" value="hello"/>
                  </when>
                </choice>
              </flow>
            </mule>
            """.trimIndent(),
        )
        val registry = BabelfishRegistry().apply {
            registerSource("", "scheduler", SchedulerSourceMapper)
            registerElement("", "choice", ChoiceMapper)
            registerElement("", "set-payload", SetPayloadMapper)
        }
        val result = MuseBabelfish.translateXmlToMuseKotlinDsl(path, registry)
        val file = KotlinCodeEmitter().apply {
            registerSourceEmitter(SchedulerConfig::class, KotlinPoetSchedulerSourceCodeEmitter)
            registerElementEmitter(ChoiceConfig::class, ChoiceCodeEmitter)
            registerElementEmitter(SetPayloadConfig::class, SetPayloadCodeEmitter)
        }.emitFile(result, "com.example")

        val flow = assertIs<MuseFlow.Flow>(result.flows.single())
        assertIs<SchedulerConfig>(flow.source)
        val choice = assertIs<ChoiceConfig>(flow.pipeline.single())
        val payload = assertIs<SetPayloadConfig>(choice.whenRoutes.single().pipeline.single())
        assertEquals("hello", payload.value)
        val source = file.toString()
        for (extension in listOf("scheduler", "choice", "setPayload")) {
            assertContains(source, "import dev.museframework.dsl.dsl.$extension")
        }
    }

    @Test
    fun `provider id is core`() {
        assertEquals("core", CoreMapperProvider().id)
    }

    @Test
    fun `provider has a public no-argument constructor`() {
        val constructor = CoreMapperProvider::class.java.getConstructor()

        assertTrue(Modifier.isPublic(constructor.modifiers))
        assertEquals(CoreMapperProvider::class.java, constructor.newInstance()::class.java)
    }

    @Test
    fun `provider is discovered exactly once by its classloader`() {
        val discovered =
            ServiceLoader
                .load(BabelfishMapperProvider::class.java, CoreMapperProvider::class.java.classLoader)
                .toList()
        val implementations = discovered.filter { it::class == CoreMapperProvider::class }

        assertEquals(1, implementations.size)
        assertEquals("core", implementations.single().id)
        assertEquals(1, discovered.count { it.id == "core" })
    }

    @Test
    fun `provider registers core mappings on independent registries`(@TempDir dir: Path) {
        repeat(2) { index ->
            val registry = BabelfishRegistry().apply { CoreMapperProvider().registerMappings(this) }
            val file = dir.resolve("core-flow-$index.xml").toFile()
            file.writeText(
                """
                <mule xmlns="http://www.mulesoft.org/schema/mule/core">
                  <flow name="hello-flow">
                    <set-payload value="Hello"/>
                  </flow>
                </mule>
                """.trimIndent(),
            )

            val result = MuseBabelfish.translateXmlToMuseKotlinDsl(file.toPath(), registry)

            assertEquals("hello-flow", result.flows.single().name)
        }
    }

    @Test
    fun `provider registers core emitters repeatedly and accepts native build`(@TempDir dir: Path) {
        listOf(false, true).forEachIndexed { index, nativeBuild ->
            val registry = BabelfishRegistry().apply { CoreMapperProvider().registerMappings(this) }
            val file = dir.resolve("core-emitter-$index.xml").toFile()
            file.writeText(
                """
                <mule xmlns="http://www.mulesoft.org/schema/mule/core">
                  <flow name="hello-flow">
                    <set-payload value="Hello"/>
                  </flow>
                </mule>
                """.trimIndent(),
            )
            val result = MuseBabelfish.translateXmlToMuseKotlinDsl(file.toPath(), registry)
            val emitter = KotlinCodeEmitter().apply {
                CoreMapperProvider().registerEmitters(this, nativeBuild)
            }

            assertContains(emitter.emit(result, "com.example"), "setPayload")
        }
    }
}
