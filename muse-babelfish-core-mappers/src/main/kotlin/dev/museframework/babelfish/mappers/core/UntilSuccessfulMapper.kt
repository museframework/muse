package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <until-successful> elements to UntilSuccessfulConfig.
 *
 * <until-successful maxRetries="5" millisBetweenRetries="1000">
 *   <http:request config-ref="Config" path="/api/unstable"/>
 * </until-successful>
 */
object UntilSuccessfulMapper : ElementMapper<UntilSuccessfulConfig> {
    override fun map(element: RawElement, ctx: MappingContext): UntilSuccessfulConfig {
        val pipeline = element.children.map { child -> ctx.mapPipelineElement(child) }

        return UntilSuccessfulConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            maxRetries = element.attr("maxRetries")?.toIntOrNull() ?: 5,
            millisBetweenRetries = element.attr("millisBetweenRetries")?.toLongOrNull() ?: 1000L,
            pipeline = pipeline
        )
    }
}

/**
 * Emits Kotlin DSL code for UntilSuccessfulConfig.
 */
object UntilSuccessfulCodeEmitter : ElementCodeEmitter<UntilSuccessfulConfig> {
    override fun emit(element: UntilSuccessfulConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.untilSuccessful")
        val codeBlock = CodeBlock.builder().apply {
            add("untilSuccessful·{")
            if (element.maxRetries != 5 || element.millisBetweenRetries != 1000L || element.pipeline.isNotEmpty()) {
                add("\n")
                if (element.maxRetries != 5) add("    maxRetries·=·%L\n", element.maxRetries)
                if (element.millisBetweenRetries != 1000L) {
                    add("    millisBetweenRetries·=·%L\n", element.millisBetweenRetries)
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
