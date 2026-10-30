package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.RaiseErrorConfig
import com.squareup.kotlinpoet.CodeBlock
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
    override fun emit(element: RaiseErrorConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.raiseError")
        val codeBlock = CodeBlock.builder().apply {
            add("raiseError·{\n")
            add("    type·=·%S\n", element.type)
            if (element.description.isNotEmpty()) {
                add("    description·=·%S\n", element.description)
            }
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
