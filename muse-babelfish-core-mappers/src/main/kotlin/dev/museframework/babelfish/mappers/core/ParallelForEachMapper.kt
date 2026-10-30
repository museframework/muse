package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.TargetConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <parallel-foreach> elements to ParallelForEachConfig.
 *
 * <parallel-foreach collection="#[payload.items]" maxConcurrency="4" timeout="5000">
 *   <set-payload value='#["Processed: " ++ payload]'/>
 * </parallel-foreach>
 */
object ParallelForEachMapper : ElementMapper<ParallelForEachConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ParallelForEachConfig {
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }

        return ParallelForEachConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            collection = element.attr("collection"),
            timeout = element.attr("timeout")?.toLongOrNull(),
            maxConcurrency = element.attr("maxConcurrency")?.toIntOrNull(),
            targetConfig =
                element.attr("target")?.let { target ->
                    TargetConfig(target = target, targetValue = element.attr("targetValue"))
                },
            pipeline = pipeline
        )
    }
}

/**
 * Emits Kotlin DSL code for ParallelForEachConfig.
 */
object ParallelForEachCodeEmitter : ElementCodeEmitter<ParallelForEachConfig> {
    override fun emit(element: ParallelForEachConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.parallelForEach")
        val codeBlock = CodeBlock.builder().apply {
            add("parallelForEach·{")
            if (
                element.collection != null ||
                element.timeout != null ||
                element.maxConcurrency != null ||
                element.targetConfig != null ||
                element.pipeline.isNotEmpty()
            ) {
                add("\n")
                element.collection?.let { add("    collection·=·%S\n", it) }
                element.timeout?.let { add("    timeout·=·%L\n", it) }
                element.maxConcurrency?.let { add("    maxConcurrency·=·%L\n", it) }
                element.targetConfig?.let { target ->
                    add("    target·=·%S\n", target.target)
                    target.targetValue?.let { add("    targetValue·=·%S\n", it) }
                }
                if (element.pipeline.isNotEmpty()) {
                    add("%L", ctx.emitPipelineElements(element.pipeline, "    "))
                }
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
