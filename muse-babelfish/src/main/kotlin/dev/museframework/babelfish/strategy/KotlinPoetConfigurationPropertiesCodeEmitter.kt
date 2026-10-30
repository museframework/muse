package dev.museframework.babelfish.strategy

import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.dsl.api.config.ConfigurationPropertiesConfig
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for ConfigurationPropertiesConfig.
 *
 * Generates DSL code like:
 * ```
 * configurationProperties(file = "properties/config.yaml")
 * ```
 */
object KotlinPoetConfigurationPropertiesCodeEmitter : ConfigCodeEmitter<ConfigurationPropertiesConfig> {

    override fun emit(config: ConfigurationPropertiesConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.dsl.dsl.configurationProperties")

        val codeBlock = CodeBlock.builder()
            .addStatement("configurationProperties(file = %S)", config.file)
            .build()

        return codeBlock.toString()
    }
}
