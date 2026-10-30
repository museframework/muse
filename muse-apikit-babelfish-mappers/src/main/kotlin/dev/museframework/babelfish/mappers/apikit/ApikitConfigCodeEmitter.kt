package dev.museframework.babelfish.mappers.apikit

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter

/**
 * Code emitter for [ApikitConfig] that generates Kotlin DSL.
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
object ApikitConfigCodeEmitter : ConfigCodeEmitter<ApikitConfig> {
    override fun emit(
        config: ApikitConfig,
        ctx: CodegenContext,
    ): String {
        ctx.addImport("dev.museframework.apikit.api.dsl.apikitConfig")

        return buildString {
            appendLine("apikitConfig {")
            appendLine("    name = \"${config.name}\"")
            appendLine("    docName = \"${config.docName}\"")
            appendLine("    docId = \"${config.docId}\"")

            config.docDescription?.let {
                appendLine("    docDescription = \"$it\"")
            }

            config.sourceFile?.let {
                appendLine("    sourceFile = \"$it\"")
            }

            config.sourceLine?.let {
                appendLine("    sourceLine = $it")
            }

            config.sourceClass?.let {
                appendLine("    sourceClass = \"$it\"")
            }

            config.spec?.let {
                appendLine("    spec = \"$it\"")
            }

            if (config.disableValidations) {
                appendLine("    disableValidations = true")
            }

            append("}")
        }
    }
}
