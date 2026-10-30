package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.SetPayloadConfig
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(element: SetPayloadConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.setPayload")
        val codeBlock = CodeBlock.builder().apply {
            add("setPayload·{\n")
            add("    value·=·%S\n", element.value)
            element.mimeType?.let { add("    mimeType·=·%S\n", it) }
            element.encoding?.let { add("    encoding·=·%S\n", it) }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
