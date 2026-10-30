package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(
        element: FlowRefConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.dsl.dsl.flowRef")
        return when (val ref = element.ref) {
            is FlowRef.ByName -> {
                val codeBlock = CodeBlock.builder().apply {
                    add("flowRef(%S)·{", ref.name)
                    element.targetConfig?.let { target ->
                        ctx.addImport("dev.museframework.dsl.api.config.TargetConfig")
                        add("\n    targetConfig·=·TargetConfig(%S", target.target)
                        target.targetValue?.let { add(",·%S", it) }
                        add(")\n")
                    }
                    add("}")
                }.build()
                codeBlock.toString()
            }

            is FlowRef.ByReference -> {
                // XML mapping always produces ByName - reference requires resolution
                // This branch is included for exhaustiveness but should not be reached
                "flowRef(/* reference */) { }"
            }
        }
    }
}
