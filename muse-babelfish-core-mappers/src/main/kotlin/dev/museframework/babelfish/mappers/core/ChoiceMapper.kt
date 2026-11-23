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
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import java.util.UUID

/**
 * Maps Mule XML <choice> elements to ChoiceConfig.
 *
 * <choice>
 *   <when expression="#[payload == 'A']">
 *     <set-payload value='"Route A"'/>
 *   </when>
 *   <otherwise>
 *     <set-payload value='"Default"'/>
 *   </otherwise>
 * </choice>
 */
object ChoiceMapper : ElementMapper<ChoiceConfig> {
    override fun map(element: RawElement, ctx: MappingContext): ChoiceConfig {
        val whenRoutes = element.children
            .filter { it.tag == "when" }
            .map { whenEl ->
                val expression = whenEl.requireAttr("expression", whenEl.location)
                val pipeline = whenEl.children.map { child -> ctx.mapPipelineElement(child) }
                ChoiceRoute(
                    expression = expression,
                    pipeline = pipeline
                )
            }

        val otherwise = element.children
            .find { it.tag == "otherwise" }
            ?.children
            ?.map { child -> ctx.mapPipelineElement(child) }

        return ChoiceConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            whenRoutes = whenRoutes,
            otherwise = otherwise
        )
    }
}

/**
 * Emits Kotlin DSL code for ChoiceConfig.
 */
object ChoiceCodeEmitter : ElementCodeEmitter<ChoiceConfig> {
    private val choice = MemberName("dev.museframework.dsl.dsl", "choice")

    override fun emit(element: ChoiceConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", choice)
        addDocumentation(element)
        for (route in element.whenRoutes) {
            beginControlFlow("%N(%L)", "when", kotlinStringLiteral(route.expression))
            add(ctx.emitPipelineElements(route.pipeline))
            endControlFlow()
        }
        element.otherwise?.takeIf { it.isNotEmpty() }?.let { pipeline ->
            beginControlFlow("otherwise")
            add(ctx.emitPipelineElements(pipeline))
            endControlFlow()
        }
        endControlFlow()
    }
}
