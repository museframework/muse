package dev.museframework.apikit.api.builder

import dev.museframework.apikit.api.config.ApikitRef
import dev.museframework.apikit.api.config.ApikitRouter
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.UUID

@MuseDsl
class ApikitBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var configRef: ApikitRef? = null

    fun build(): ApikitRouter {
        val callSite = CallSiteCapture.capture()
        return ApikitRouter(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            configRef = configRef ?: throw IllegalArgumentException("configRef is required"),
        )
    }
}
