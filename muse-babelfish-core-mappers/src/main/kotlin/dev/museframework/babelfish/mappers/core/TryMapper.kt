package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.ErrorHandlerMapper
import dev.museframework.babelfish.mapper.ErrorHandlerStrategyCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TryConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <try> elements to TryConfig.
 *
 * <try>
 *   <http:request config-ref="Config" path="/api/data"/>
 *   <error-handler>
 *     <on-error-continue type="HTTP:CONNECTIVITY">
 *       <set-payload value='"Service unavailable"'/>
 *     </on-error-continue>
 *   </error-handler>
 * </try>
 */
object TryMapper : ElementMapper<TryConfig> {
    override fun map(element: RawElement, ctx: MappingContext): TryConfig {
        val pipelineElements = element.children
            .filter { it.tag != "error-handler" }
            .map { child -> ctx.mapPipelineElement(child) }

        val errorHandler = element.children
            .find { it.tag == "error-handler" }
            ?.let { ErrorHandlerMapper.map(it, ctx) }

        return TryConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            pipeline = pipelineElements,
            errorHandler = errorHandler,
            transactionalAction = element.attr("transactionalAction")
        )
    }
}

/**
 * Emits Kotlin DSL code for TryConfig.
 */
object TryCodeEmitter : ElementCodeEmitter<TryConfig> {
    override fun emit(element: TryConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.`try`")
        val codeBlock = CodeBlock.builder().apply {
            add("`try`·{\n")
            if (element.pipeline.isNotEmpty()) {
                add("%L", ctx.emitPipelineElements(element.pipeline, "    "))
            }
            element.errorHandler?.let { eh ->
                add("    errorHandler·{\n")
                eh.strategies.forEach { strategy ->
                    add("%L", ErrorHandlerStrategyCodeEmitter.emitStrategy(strategy, ctx).prependIndent("        "))
                    add("\n")
                }
                add("    }\n")
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
