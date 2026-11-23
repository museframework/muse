package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
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
    private val withCorrelationId = MemberName("dev.museframework.dsl.builder", "withCorrelationId")

    override fun emit(element: WithCorrelationIdConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", withCorrelationId)
        addDocumentation(element)
        addStatement("correlationId = %L", kotlinStringLiteral(element.correlationId))
        add(ctx.emitPipelineElements(element.pipeline))
        endControlFlow()
    }
}
