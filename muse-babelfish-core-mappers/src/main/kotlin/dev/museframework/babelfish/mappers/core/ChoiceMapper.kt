package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(element: ChoiceConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.choice")
        val codeBlock = CodeBlock.builder().apply {
            add("choice·{\n")
            element.whenRoutes.forEach { route ->
                add("    `when`(%S)·{\n", route.expression)
                add("%L", ctx.emitPipelineElements(route.pipeline, "        "))
                add("    }\n")
            }
            element.otherwise?.let { otherwisePipeline ->
                if (otherwisePipeline.isNotEmpty()) {
                    add("    otherwise·{\n")
                    add("%L", ctx.emitPipelineElements(otherwisePipeline, "        "))
                    add("    }\n")
                }
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
