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
import dev.museframework.dsl.api.config.SetVariableConfig
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
    private val setVariable = MemberName("dev.museframework.dsl.dsl", "setVariable")

    override fun emit(element: SetVariableConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M(%L)", setVariable, kotlinStringLiteral(element.variableName))
        addDocumentation(element)
        addStatement("value = %L", kotlinStringLiteral(element.value))
        element.mimeType?.let { addStatement("mimeType = %L", kotlinStringLiteral(it)) }
        endControlFlow()
    }
}
