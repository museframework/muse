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
import dev.museframework.dsl.api.config.SetPayloadConfig
import java.util.UUID

object SetPayloadMapper : ElementMapper<SetPayloadConfig> {
    override fun map(element: RawElement, ctx: MappingContext): SetPayloadConfig {
        return SetPayloadConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            value = element.requireAttr("value", element.location),
            mimeType = element.attr("mimeType"),
            encoding = element.attr("encoding")
        )
    }
}

object SetPayloadCodeEmitter : ElementCodeEmitter<SetPayloadConfig> {
    private val setPayload = MemberName("dev.museframework.dsl.dsl", "setPayload")

    override fun emit(element: SetPayloadConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", setPayload)
        addDocumentation(element)
        addStatement("value = %L", kotlinStringLiteral(element.value))
        element.mimeType?.let { addStatement("mimeType = %L", kotlinStringLiteral(it)) }
        element.encoding?.let { addStatement("encoding = %L", kotlinStringLiteral(it)) }
        endControlFlow()
    }
}
