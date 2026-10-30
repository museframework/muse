package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.MuseBabelfish
import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoreR8RemediationIT {
    @Test
    fun `scatter gather mapper preserves target options`(@TempDir dir: Path) {
        val config = assertIs<ScatterGatherConfig>(translate(dir, "scatter-gather", "<route><set-payload value='ok'/></route>"))

        assertEquals(TargetConfig("count", "#[sizeOf(payload)]"), config.targetConfig)
    }

    @Test
    fun `parallel foreach mapper preserves target options`(@TempDir dir: Path) {
        val config = assertIs<ParallelForEachConfig>(translate(dir, "parallel-foreach", "<set-payload value='ok'/>"))

        assertEquals(TargetConfig("count", "#[sizeOf(payload)]"), config.targetConfig)
    }

    @Test
    fun `flow reference mapper preserves target options`(@TempDir dir: Path) {
        val fixture = dir.resolve("flow.xml").toFile()
        fixture.writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core">
              <flow name="review">
                <flow-ref name="child" target="count" targetValue="#[sizeOf(payload)]"/>
              </flow>
            </mule>
            """.trimIndent(),
        )
        val registry = BabelfishRegistry().apply { CoreMapperProvider().registerMappings(this) }
        val config =
            assertIs<FlowRefConfig>(
                MuseBabelfish.translateXmlToMuseKotlinDsl(fixture.toPath(), registry).flows.single().pipeline.single(),
            )

        assertEquals(TargetConfig("count", "#[sizeOf(payload)]"), config.targetConfig)
    }

    @Test
    fun `scatter gather emitter preserves target options`() {
        val config =
            ScatterGatherConfig(
                docName = "sg",
                docId = "sg",
                routes = emptyList(),
                targetConfig = TargetConfig("count", "#[sizeOf(payload)]"),
            )

        val code = ScatterGatherCodeEmitter.emit(config, CodegenContext("review"))

        assertContains(code, "target = \"count\"")
        assertContains(code, "targetValue = \"#[sizeOf(payload)]\"")
    }

    @Test
    fun `parallel foreach emitter preserves target options`() {
        val config =
            ParallelForEachConfig(
                docName = "pfe",
                docId = "pfe",
                collection = null,
                pipeline = emptyList(),
                targetConfig = TargetConfig("count", "#[sizeOf(payload)]"),
            )

        val code = ParallelForEachCodeEmitter.emit(config, CodegenContext("review"))

        assertContains(code, "target = \"count\"")
        assertContains(code, "targetValue = \"#[sizeOf(payload)]\"")
    }

    @Test
    fun `flow reference emitter preserves target options`() {
        val config =
            FlowRefConfig(
                docName = "flow-ref",
                docId = "flow-ref",
                ref = FlowRef.ByName("child"),
                targetConfig = TargetConfig("count", "#[sizeOf(payload)]"),
            )

        val code = FlowRefCodeEmitter.emit(config, CodegenContext("review"))

        assertContains(code, "flowRef(\"child\") {")
        assertContains(code, "targetConfig = TargetConfig(\"count\", \"#[sizeOf(payload)]\")")
    }

    @Test
    fun `try emitter uses the Kotlin DSL try symbol`() {
        val context = CodegenContext("review")
        val code =
            TryCodeEmitter.emit(
                TryConfig(
                    docName = "try",
                    docId = "try",
                    pipeline = emptyList(),
                    errorHandler = null,
                ),
                context,
            )

        assertContains(code, "`try` {")
        assertContains(context.resolvedImports().single(), "dev.museframework.dsl.dsl.`try`")
    }

    @Test
    fun `parallel router emitters escape target expressions as Kotlin strings`() {
        val target = TargetConfig("result\"\$name", "#[\"line one\\n\$payload\"]")
        val scatterCode =
            ScatterGatherCodeEmitter.emit(
                ScatterGatherConfig(docName = "sg", docId = "sg", routes = emptyList(), targetConfig = target),
                CodegenContext("review"),
            )
        val parallelCode =
            ParallelForEachCodeEmitter.emit(
                ParallelForEachConfig(docName = "pfe", docId = "pfe", collection = null, pipeline = emptyList(), targetConfig = target),
                CodegenContext("review"),
            )

        listOf(scatterCode, parallelCode).forEach { code ->
            assertContains(code, "result\\\"")
            assertContains(code, "{'$'}name")
            assertContains(code, "{'$'}payload")
            assertContains(code, "\\\\n")
        }
    }

    private fun translate(
        dir: Path,
        tag: String,
        body: String,
    ): Any {
        val fixture = dir.resolve("flow.xml").toFile()
        fixture.writeText(
            """
            <mule xmlns="http://www.mulesoft.org/schema/mule/core">
              <flow name="review">
                <$tag target="count" targetValue="#[sizeOf(payload)]">$body</$tag>
              </flow>
            </mule>
            """.trimIndent(),
        )
        val registry = BabelfishRegistry().apply { CoreMapperProvider().registerMappings(this) }
        return MuseBabelfish.translateXmlToMuseKotlinDsl(fixture.toPath(), registry).flows.single().pipeline.single()
    }
}
