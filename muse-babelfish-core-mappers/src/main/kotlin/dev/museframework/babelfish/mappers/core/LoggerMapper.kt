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
import dev.museframework.dsl.api.config.LogLevel
import dev.museframework.dsl.api.config.LoggerConfig
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
    private val logger = MemberName("dev.museframework.engine.components.processor.dsl", "logger")

    override fun emit(element: LoggerConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", logger)
        addDocumentation(element)
        addStatement("message = %L", kotlinStringLiteral(element.message))
        addStatement("level = %T.%N", LogLevel::class, element.level.name)
        endControlFlow()
    }
}
