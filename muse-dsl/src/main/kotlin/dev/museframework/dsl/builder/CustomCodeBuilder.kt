package dev.museframework.dsl.builder

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.CustomCodeConfig
import dev.museframework.dsl.api.config.CustomCodeFunction
import dev.museframework.dsl.api.model.component.Processor
import java.util.UUID

@MuseDsl
class CustomCodeBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var handleErrors: Boolean = true
    private var codeFunction: CustomCodeFunction = { it }

    fun code(block: suspend (Any?) -> Any?) {
        codeFunction = block
    }

    fun build(): CustomCodeConfig {
        val callSite = CallSiteCapture.capture()
        return CustomCodeConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            code = codeFunction,
            handleErrors = handleErrors,
        )
    }
}
