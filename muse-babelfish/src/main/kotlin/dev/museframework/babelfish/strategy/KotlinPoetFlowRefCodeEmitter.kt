package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for FlowRefConfig.
 *
 * Generates DSL code like:
 * ```
 * flowRef("flow-name") {
 * }
 * ```
 */
object KotlinPoetFlowRefCodeEmitter : ElementCodeEmitter<FlowRefConfig> {

    override fun emit(element: FlowRefConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.flowRef")

        val codeBlock = CodeBlock.builder()
            .apply {
                when (val ref = element.ref) {
                    is FlowRef.ByName -> {
                        addStatement("flowRef(%S) {", ref.name)
                    }
                    is FlowRef.ByReference -> {
                        // XML mapping always produces ByName - reference requires resolution
                        // This branch is included for exhaustiveness but should not be reached
                        addStatement("flowRef(/* reference */) {")
                    }
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}
