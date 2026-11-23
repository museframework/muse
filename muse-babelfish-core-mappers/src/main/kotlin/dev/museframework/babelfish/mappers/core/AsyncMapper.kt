package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.AsyncConfig
import java.util.UUID

/**
 * Maps Mule XML <async> elements to AsyncConfig.
 *
 * <async maxConcurrency="4">
 *   <logger level="INFO" message="Async processing"/>
 * </async>
 */
object AsyncMapper : ElementMapper<AsyncConfig> {
    override fun map(element: RawElement, ctx: MappingContext): AsyncConfig {
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }

        return AsyncConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            pipeline = pipeline,
            maxConcurrency = element.attr("maxConcurrency")?.toIntOrNull()
        )
    }
}

/**
 * Emits Kotlin DSL code for AsyncConfig.
 */
object AsyncCodeEmitter : ElementCodeEmitter<AsyncConfig> {
    private val async = MemberName("dev.museframework.dsl.dsl", "async")

    override fun emit(element: AsyncConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", async)
        addDocumentation(element)
        element.maxConcurrency?.let { addStatement("maxConcurrency = %L", it) }
        add(ctx.emitPipelineElements(element.pipeline))
        endControlFlow()
    }
}
