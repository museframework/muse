package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import com.squareup.kotlinpoet.CodeBlock

/**
 * KotlinPoet-based code emitter for [ApikitConfig] that generates Kotlin DSL.
 *
 * Generated DSL Pattern:
 * ```kotlin
 * apikitConfig {
 *     name = "apiConfig"
 *     docName = "APIKit Config"
 *     docId = "abc-123"
 *     spec = "api/api-spec.raml"
 *     disableValidations = false
 * }
 * ```
 */
object KotlinPoetApikitConfigCodeEmitter : ConfigCodeEmitter<ApikitConfig> {

    override fun emit(config: ApikitConfig, ctx: CodegenContext): String {
        ctx.addImport("dev.museframework.apikit.api.dsl.apikitConfig")

        val codeBlock = CodeBlock.builder()
            .addStatement("apikitConfig {")
            .addStatement("    name = %S", config.name)
            .apply {
                if (config.docName.isNotBlank()) {
                    addStatement("    docName = %S", config.docName)
                }
            }
            .addStatement("    docId = %S", config.docId)
            .apply {
                config.docDescription?.let {
                    addStatement("    docDescription = %S", it)
                }
                config.sourceFile?.let {
                    addStatement("    sourceFile = %S", it)
                }
                config.sourceLine?.let {
                    addStatement("    sourceLine = %L", it)
                }
                config.sourceClass?.let {
                    addStatement("    sourceClass = %S", it)
                }
                config.spec?.let {
                    addStatement("    spec = %S", it)
                }
                if (config.disableValidations) {
                    addStatement("    disableValidations = true")
                }
            }
            .add("}")
            .build()

        return codeBlock.toString()
    }
}
