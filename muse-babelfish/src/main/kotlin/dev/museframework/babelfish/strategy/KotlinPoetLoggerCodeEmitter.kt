package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ElementCodeEmitter
import dev.museframework.dsl.api.config.LoggerConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for LoggerConfig.
 *
 * Generates DSL code like:
 * ```
 * logger {
 *     message = "Hello World"
 *     level = LogLevel.INFO
 * }
 * ```
 */
object KotlinPoetLoggerCodeEmitter : ElementCodeEmitter<LoggerConfig> {

    override fun emit(element: LoggerConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.engine.components.processor.dsl.logger")
        ctx.addImport("dev.museframework.dsl.api.config.LogLevel")

        val codeBlock = CodeBlock.builder()
            .addStatement("logger {")
            .addStatement("    message = %S", element.message)
            .apply {
                if (element.level != dev.museframework.dsl.api.config.LogLevel.INFO) {
                    addStatement("    level = LogLevel.%L", element.level.name)
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}
