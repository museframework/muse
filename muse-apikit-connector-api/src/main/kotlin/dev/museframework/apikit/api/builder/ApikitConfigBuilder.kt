package dev.museframework.apikit.api.builder

import dev.museframework.apikit.api.config.ApikitConfig
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import java.util.UUID

@MuseDsl
class ApikitConfigBuilder {
    var name: String? = null
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    /** Required classpath path of the RAML specification used to build APIKit routes. */
    var spec: String? = null

    /** Disables request header and query-parameter validation; specification bootstrap remains mandatory. */
    var disableValidations: Boolean = false

    fun build(): ApikitConfig {
        val callSite = CallSiteCapture.capture()
        return ApikitConfig(
            name = name ?: throw IllegalArgumentException("name is required"),
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            spec = spec,
            disableValidations = disableValidations,
        )
    }
}
