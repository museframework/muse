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
import dev.museframework.dsl.api.config.ForEachConfig
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
    private val forEach = MemberName("dev.museframework.dsl.dsl", "forEach")

    override fun emit(element: ForEachConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", forEach)
        addDocumentation(element)
        element.collection?.let { addStatement("collection = %L", kotlinStringLiteral(it)) }
        if (element.batchSize != 1) addStatement("batchSize = %L", element.batchSize)
        element.batchSizeExpression?.let { addStatement("batchSizeExpression = %L", kotlinStringLiteral(it)) }
        if (element.rootMessageVariableName != "rootMessage") {
            addStatement("rootMessageVariableName = %L", kotlinStringLiteral(element.rootMessageVariableName))
        }
        if (element.counterVariableName != "counter") {
            addStatement("counterVariableName = %L", kotlinStringLiteral(element.counterVariableName))
        }
        add(ctx.emitPipelineElements(element.pipeline))
        endControlFlow()
    }
}
