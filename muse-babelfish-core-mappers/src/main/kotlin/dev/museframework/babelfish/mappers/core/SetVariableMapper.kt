package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.SetVariableConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

object SetVariableMapper : ElementMapper<SetVariableConfig> {
    override fun map(element: RawElement, ctx: MappingContext): SetVariableConfig {
        return SetVariableConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            variableName = element.requireAttr("variableName", element.location),
            value = element.requireAttr("value", element.location),
            mimeType = element.attr("mimeType")
        )
    }
}

object SetVariableCodeEmitter : ElementCodeEmitter<SetVariableConfig> {
    override fun emit(element: SetVariableConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.setVariable")
        val codeBlock = CodeBlock.builder().apply {
            add("setVariable(%S)·{\n", element.variableName)
            add("    value·=·%S\n", element.value)
            element.mimeType?.let { add("    mimeType·=·%S\n", it) }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
