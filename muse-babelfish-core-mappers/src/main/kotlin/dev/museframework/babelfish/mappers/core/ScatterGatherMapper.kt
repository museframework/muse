package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinNumericLiteral
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.ScatterGatherRoute
import dev.museframework.dsl.api.config.TargetConfig
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
    private val scatterGather = MemberName("dev.museframework.dsl.dsl", "scatterGather")

    override fun emit(element: ScatterGatherConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", scatterGather)
        addDocumentation(element)
        element.timeout?.let { addStatement("timeout = %L", kotlinNumericLiteral(it)) }
        element.maxConcurrency?.let { addStatement("maxConcurrency = %L", kotlinNumericLiteral(it)) }
        element.targetConfig?.let { target ->
            addStatement("target = %L", kotlinStringLiteral(target.target))
            target.targetValue?.let { addStatement("targetValue = %L", kotlinStringLiteral(it)) }
        }
        for (route in element.routes) {
            beginControlFlow("route")
            add(ctx.emitPipelineElements(route.pipeline))
            endControlFlow()
        }
        endControlFlow()
    }
}
