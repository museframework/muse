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
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import java.util.UUID

/**
 * Maps Mule XML <flow-ref> elements to FlowRefConfig.
 *
 * <flow-ref name="sub-flow-name"/> maps to flowRef("sub-flow-name") { }
 */
object FlowRefMapper : ElementMapper<FlowRefConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): FlowRefConfig {
        val name = element.attrs["_name"] ?: element.attrs["name"] ?: element.requireAttr("name", element.location)
        return FlowRefConfig(
            docName = element.attrs["doc:name"] ?: element.attrs["name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            ref = FlowRef.ByName(name),
            targetConfig =
                element.attr("target")?.let { target ->
                    TargetConfig(target = target, targetValue = element.attr("targetValue"))
                },
        )
    }
}

/**
 * Emits Kotlin DSL code for FlowRefConfig.
 *
 * Generates: flowRef("flow-name") { }
 */
object FlowRefCodeEmitter : ElementCodeEmitter<FlowRefConfig> {
    private val flowRef = MemberName("dev.museframework.dsl.dsl", "flowRef")

    override fun emit(element: FlowRefConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        val name = when (val ref = element.ref) {
            is FlowRef.ByName -> ref.name
            is FlowRef.ByReference -> ref.flow.name
        }
        beginControlFlow("%M(%L)", flowRef, kotlinStringLiteral(name))
        addDocumentation(element)
        element.targetConfig?.let { target ->
            add("targetConfig = %T(%L", TargetConfig::class, kotlinStringLiteral(target.target))
            target.targetValue?.let { add(", %L", kotlinStringLiteral(it)) }
            add(")\n")
        }
        endControlFlow()
    }
}
