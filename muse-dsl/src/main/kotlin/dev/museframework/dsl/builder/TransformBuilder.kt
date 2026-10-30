package dev.museframework.dsl.builder

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.SetVariableTransformConfig
import dev.museframework.dsl.api.config.TransformConfig
import dev.museframework.dsl.api.expression.DwExpression
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.*

@MuseDsl
class TransformBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var setPayload: DwExpression? = null
    var setAttributes: DwExpression? = null
    private val setVariables = mutableListOf<SetVariableTransformConfig>()

    fun setVariable(
        variableName: String,
        expression: DwExpression,
    ) {
        setVariables.add(SetVariableTransformConfig(variableName, expression))
    }

    fun build(): TransformConfig {
        val callSite = CallSiteCapture.capture()
        return TransformConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            setPayload = setPayload,
            setAttributes = setAttributes,
            setVariables = setVariables.toList(),
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}
