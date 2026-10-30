package dev.museframework.dsl.builder

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.RaiseErrorConfig
import dev.museframework.dsl.api.model.component.Processor
import java.util.*

@MuseDsl
class RaiseErrorBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var type: String = ""
    var description: String = ""

    fun build(): RaiseErrorConfig {
        val callSite = CallSiteCapture.capture()
        return RaiseErrorConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            type = type,
            description = description,
        )
    }
}
