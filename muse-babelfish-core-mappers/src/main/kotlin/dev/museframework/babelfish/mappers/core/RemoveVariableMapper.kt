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
import dev.museframework.dsl.api.config.RemoveVariableConfig
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
 * Generates: removeVariable("myVar") { }
 */
object RemoveVariableCodeEmitter : ElementCodeEmitter<RemoveVariableConfig> {
    private val removeVariable = MemberName("dev.museframework.dsl.dsl", "removeVariable")

    override fun emit(element: RemoveVariableConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M(%L)", removeVariable, kotlinStringLiteral(element.variableName))
        addDocumentation(element)
        endControlFlow()
    }
}
