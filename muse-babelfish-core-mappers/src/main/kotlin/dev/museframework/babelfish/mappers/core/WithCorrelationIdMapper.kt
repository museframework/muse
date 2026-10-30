package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <with-correlation-id> elements to WithCorrelationIdConfig.
 *
 * <with-correlation-id value="#[vars.correlationId]">
 *   <logger level="INFO" message="Processing with correlationId"/>
 * </with-correlation-id>
 */
object WithCorrelationIdMapper : ElementMapper<WithCorrelationIdConfig> {
    override fun map(element: RawElement, ctx: MappingContext): WithCorrelationIdConfig {
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }

        return WithCorrelationIdConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            correlationId = element.requireAttr("value", element.location),
            pipeline = pipeline
        )
    }
}

/**
 * Emits Kotlin DSL code for WithCorrelationIdConfig.
 */
object WithCorrelationIdCodeEmitter : ElementCodeEmitter<WithCorrelationIdConfig> {
    override fun emit(element: WithCorrelationIdConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.withCorrelationId")
        val codeBlock = CodeBlock.builder().apply {
            add("withCorrelationId(%S)·{\n", element.correlationId)
            if (element.pipeline.isNotEmpty()) {
                add("%L", ctx.emitPipelineElements(element.pipeline, "    "))
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
