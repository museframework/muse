package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.RemoveVariableConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

/**
 * Maps Mule XML <remove-variable> elements to RemoveVariableConfig.
 *
 * <remove-variable variableName="myVar"/>
 */
object RemoveVariableMapper : ElementMapper<RemoveVariableConfig> {
    override fun map(element: RawElement, ctx: MappingContext): RemoveVariableConfig {
        return RemoveVariableConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            variableName = element.requireAttr("variableName", element.location)
        )
    }
}

/**
 * Emits Kotlin DSL code for RemoveVariableConfig.
 *
 * Generates: removeVariable { variableName = "myVar" }
 */
object RemoveVariableCodeEmitter : ElementCodeEmitter<RemoveVariableConfig> {
    override fun emit(element: RemoveVariableConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.engine.components.processor.dsl.removeVariable")
        val codeBlock = CodeBlock.builder().apply {
            add("removeVariable·{\n")
            add("    variableName·=·%S\n", element.variableName)
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
