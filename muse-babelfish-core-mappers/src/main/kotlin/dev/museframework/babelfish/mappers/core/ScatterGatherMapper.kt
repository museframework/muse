package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.ScatterGatherRoute
import dev.museframework.dsl.api.config.TargetConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <scatter-gather> elements to ScatterGatherConfig.
 *
 * <scatter-gather timeout="5000" maxConcurrency="4">
 *   <route>
 *     <http:request config-ref="Config" path="/service-a"/>
 *   </route>
 *   <route>
 *     <http:request config-ref="Config" path="/service-b"/>
 *   </route>
 * </scatter-gather>
 */
object ScatterGatherMapper : ElementMapper<ScatterGatherConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ScatterGatherConfig {
        val routes = element.children
            .filter { it.tag == "route" }
            .map { routeEl ->
                val pipeline = routeEl.children.map { child -> ctx.mapPipelineElement(child) }
                ScatterGatherRoute(pipeline = pipeline)
            }

        return ScatterGatherConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            routes = routes,
            timeout = element.attr("timeout")?.toLongOrNull(),
            maxConcurrency = element.attr("maxConcurrency")?.toIntOrNull(),
            targetConfig =
                element.attr("target")?.let { target ->
                    TargetConfig(target = target, targetValue = element.attr("targetValue"))
                },
        )
    }
}

/**
 * Emits Kotlin DSL code for ScatterGatherConfig.
 */
object ScatterGatherCodeEmitter : ElementCodeEmitter<ScatterGatherConfig> {
    override fun emit(element: ScatterGatherConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.scatterGather")
        val codeBlock = CodeBlock.builder().apply {
            add("scatterGather·{\n")
            element.timeout?.let { add("    timeout·=·%L\n", it) }
            element.maxConcurrency?.let { add("    maxConcurrency·=·%L\n", it) }
            element.targetConfig?.let { target ->
                add("    target·=·%S\n", target.target)
                target.targetValue?.let { add("    targetValue·=·%S\n", it) }
            }
            element.routes.forEach { route ->
                add("    route·{\n")
                add("%L", ctx.emitPipelineElements(route.pipeline, "        "))
                add("    }\n")
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
