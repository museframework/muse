package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.AsyncConfig
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(element: AsyncConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.async")
        val codeBlock = CodeBlock.builder().apply {
            add("async·{")
            if (element.maxConcurrency != null || element.pipeline.isNotEmpty()) {
                add("\n")
                element.maxConcurrency?.let { add("    maxConcurrency·=·%L\n", it) }
                if (element.pipeline.isNotEmpty()) {
                    add("%L", ctx.emitPipelineElements(element.pipeline, "    "))
                }
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
