package dev.museframework.babelfish.mappers.core

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.kotlinStringLiteral
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig

object ConfigurationPropertiesCodeEmitter : ConfigCodeEmitter<ConfigurationPropertiesConfig> {
    override fun emit(config: ConfigurationPropertiesConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        add("%T(\n", ConfigurationPropertiesConfig::class)
        indent()
        addStatement("docName = %L,", kotlinStringLiteral(config.docName))
        addStatement("docId = %L,", kotlinStringLiteral(config.docId))
        addStatement("file = %L,", kotlinStringLiteral(config.file))
        unindent()
        add(")")
    }
}
