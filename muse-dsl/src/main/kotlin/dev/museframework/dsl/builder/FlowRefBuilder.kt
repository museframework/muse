package dev.museframework.dsl.builder

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import java.util.*

@MuseDsl
class FlowRefBuilder(private val ref: FlowRef) {
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var targetConfig: TargetConfig? = null

    fun build(): FlowRefConfig {
        val callSite = CallSiteCapture.capture()
        return FlowRefConfig(
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            ref = ref,
            targetConfig = targetConfig,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}
