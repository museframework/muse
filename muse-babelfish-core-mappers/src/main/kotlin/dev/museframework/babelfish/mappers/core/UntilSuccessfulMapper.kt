package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
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
    private val untilSuccessful = MemberName("dev.museframework.dsl.dsl", "untilSuccessful")

    override fun emit(element: UntilSuccessfulConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", untilSuccessful)
        addDocumentation(element)
        if (element.maxRetries != 5) addStatement("maxRetries = %L", element.maxRetries)
        if (element.millisBetweenRetries != 1000L) {
            addStatement("millisBetweenRetries = %L", element.millisBetweenRetries)
        }
        add(ctx.emitPipelineElements(element.pipeline))
        endControlFlow()
    }
}
