package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.ForEachConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <foreach> elements to ForEachConfig.
 *
 * <foreach collection="#[payload.items]" batchSize="10" rootMessageVariableName="rootMsg" counterVariableName="idx">
 *   <set-payload value='#["Processed: " ++ payload]'/>
 * </foreach>
 */
object ForEachMapper : ElementMapper<ForEachConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ForEachConfig {
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }

        return ForEachConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            collection = element.attr("collection"),
            batchSize = element.attr("batchSize")?.toIntOrNull() ?: 1,
            batchSizeExpression = element.attr("batchSize")?.takeIf { it.toIntOrNull() == null },
            rootMessageVariableName = element.attr("rootMessageVariableName") ?: "rootMessage",
            counterVariableName = element.attr("counterVariableName") ?: "counter",
            pipeline = pipeline
        )
    }
}

/**
 * Emits Kotlin DSL code for ForEachConfig.
 */
object ForEachCodeEmitter : ElementCodeEmitter<ForEachConfig> {
    override fun emit(element: ForEachConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.forEach")
        val codeBlock = CodeBlock.builder().apply {
            add("forEach·{")
            if (element.collection != null || element.batchSize != 1 || element.batchSizeExpression != null ||
                element.rootMessageVariableName != "rootMessage" || 
                element.counterVariableName != "counter" || 
                element.pipeline.isNotEmpty()) {
                add("\n")
                element.collection?.let { add("    collection·=·%S\n", it) }
                if (element.batchSize != 1) add("    batchSize·=·%L\n", element.batchSize)
                element.batchSizeExpression?.let { add("    batchSizeExpression·=·%S\n", it) }
                if (element.rootMessageVariableName != "rootMessage") {
                    add("    rootMessageVariableName·=·%S\n", element.rootMessageVariableName)
                }
                if (element.counterVariableName != "counter") {
                    add("    counterVariableName·=·%S\n", element.counterVariableName)
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
