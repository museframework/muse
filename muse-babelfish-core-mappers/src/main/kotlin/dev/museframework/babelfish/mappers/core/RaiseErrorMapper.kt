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
import dev.museframework.dsl.api.config.RaiseErrorConfig
import java.util.UUID

/**
 * Maps Mule XML <raise-error> elements to RaiseErrorConfig.
 *
 * <raise-error type="MULE:EXPRESSION" description="Intentional failure"/>
 */
object RaiseErrorMapper : ElementMapper<RaiseErrorConfig> {
    override fun map(element: RawElement, ctx: MappingContext): RaiseErrorConfig {
        return RaiseErrorConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            type = element.requireAttr("type", element.location),
            description = element.attr("description") ?: ""
        )
    }
}

/**
 * Emits Kotlin DSL code for RaiseErrorConfig.
 *
 * Generates: raiseError { type = "MULE:EXPRESSION"; description = "Intentional failure" }
 */
object RaiseErrorCodeEmitter : ElementCodeEmitter<RaiseErrorConfig> {
    private val raiseError = MemberName("dev.museframework.dsl.dsl", "raiseError")

    override fun emit(element: RaiseErrorConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", raiseError)
        addDocumentation(element)
        addStatement("type = %L", kotlinStringLiteral(element.type))
        if (element.description.isNotEmpty()) {
            addStatement("description = %L", kotlinStringLiteral(element.description))
        }
        endControlFlow()
    }
}
