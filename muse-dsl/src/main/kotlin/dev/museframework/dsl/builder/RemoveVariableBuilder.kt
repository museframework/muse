package dev.museframework.dsl.builder

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.RemoveVariableConfig
import dev.museframework.dsl.api.model.component.Processor
import java.util.*

@MuseDsl
class RemoveVariableBuilder(private val variableName: String) {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null

    fun build(): RemoveVariableConfig {
        val callSite = CallSiteCapture.capture()
        return RemoveVariableConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            variableName = variableName,
        )
    }
}
