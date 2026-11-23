package dev.museframework.babelfish.mappers.apikit

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.kotlinNumericLiteral
import dev.museframework.babelfish.emitter.kotlinStringLiteral

object ApikitConfigCodeEmitter : ConfigCodeEmitter<ApikitConfig> {
    private val apikitConfig = MemberName("dev.museframework.apikit.api.dsl", "apikitConfig")

    override fun emit(config: ApikitConfig, ctx: CodegenContext): CodeBlock = buildCodeBlock {
        add("%M {\n", apikitConfig)
        indent()
        addStatement("name = %L", kotlinStringLiteral(config.name))
        addStatement("docName = %L", kotlinStringLiteral(config.docName))
        addStatement("docId = %L", kotlinStringLiteral(config.docId))
        config.docDescription?.let { addStatement("docDescription = %L", kotlinStringLiteral(it)) }
        config.sourceFile?.let { addStatement("sourceFile = %L", kotlinStringLiteral(it)) }
        config.sourceLine?.let { addStatement("sourceLine = %L", kotlinNumericLiteral(it)) }
        config.sourceClass?.let { addStatement("sourceClass = %L", kotlinStringLiteral(it)) }
        config.spec?.let { addStatement("spec = %L", kotlinStringLiteral(it)) }
        if (config.disableValidations) addStatement("disableValidations = true")
        unindent()
        add("}")
    }
}
