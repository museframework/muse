package dev.museframework.dsl.builder

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.SetVariableConfig
import dev.museframework.dsl.api.model.component.Processor
import java.util.*

@MuseDsl
class SetVariableBuilder(private val variableName: String) {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var value: String = ""
    var mimeType: String? = null

    fun build(): SetVariableConfig {
        val callSite = CallSiteCapture.capture()
        return SetVariableConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            variableName = variableName,
            value = value,
            mimeType = mimeType,
        )
    }
}
