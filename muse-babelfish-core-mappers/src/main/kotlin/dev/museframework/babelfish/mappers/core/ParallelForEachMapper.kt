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
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.TargetConfig
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
    private val parallelForEach = MemberName("dev.museframework.dsl.dsl", "parallelForEach")

    override fun emit(element: ParallelForEachConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", parallelForEach)
        addDocumentation(element)
        element.collection?.let { addStatement("collection = %L", kotlinStringLiteral(it)) }
        element.timeout?.let { addStatement("timeout = %L", kotlinNumericLiteral(it)) }
        element.maxConcurrency?.let { addStatement("maxConcurrency = %L", kotlinNumericLiteral(it)) }
        element.targetConfig?.let { target ->
            addStatement("target = %L", kotlinStringLiteral(target.target))
            target.targetValue?.let { addStatement("targetValue = %L", kotlinStringLiteral(it)) }
        }
        add(ctx.emitPipelineElements(element.pipeline))
        endControlFlow()
    }
}
