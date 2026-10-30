package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.api.ElementMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawElement
import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
import com.squareup.kotlinpoet.CodeBlock
import java.util.UUID

object LoggerMapper : ElementMapper<LoggerConfig> {
    override fun map(
        element: RawElement,
        ctx: MappingContext,
    ): LoggerConfig {
        val levelStr = element.attr("level") ?: "INFO"
        val level =
            try {
                LogLevel.valueOf(levelStr.uppercase())
            } catch (e: IllegalArgumentException) {
                LogLevel.INFO
            }

        return LoggerConfig(
            docName = element.attrs["doc:name"] ?: "",
            docId = element.attrs["doc:id"] ?: UUID.randomUUID().toString(),
            level = level,
            message = element.requireAttr("message", element.location),
        )
    }
}

object LoggerCodeEmitter : ElementCodeEmitter<LoggerConfig> {
    override fun emit(
        element: LoggerConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.engine.components.processor.dsl.logger")
        ctx.addImport("dev.museframework.dsl.api.config.LogLevel")
        val codeBlock = CodeBlock.builder().apply {
            add("logger·{\n")
            add("    message·=·%S\n", element.message)
            add("    level·=·LogLevel.%L\n", element.level)
            add("}")
        }.build()
        return codeBlock.toString()
    }
}
