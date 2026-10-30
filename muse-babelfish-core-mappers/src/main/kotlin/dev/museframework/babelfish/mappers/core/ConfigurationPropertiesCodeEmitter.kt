package dev.museframework.babelfish.mappers.core

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import com.squareup.kotlinpoet.CodeBlock

object ConfigurationPropertiesCodeEmitter : ConfigCodeEmitter<ConfigurationPropertiesConfig> {
    override fun emit(
        config: ConfigurationPropertiesConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.dsl.api.config.ConfigurationPropertiesConfig")
        
        val codeBlock = CodeBlock.builder().apply {
            add("ConfigurationPropertiesConfig(\n")
            add("    docName·=·%S,\n", config.docName)
            add("    docId·=·%S,\n", config.docId)
            add("    file·=·%S\n", config.file)
            add(")")
        }.build()
        
        return codeBlock.toString()
    }
}
