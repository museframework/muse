package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class UntilSuccessfulBuilder : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var maxRetries: Int = 5
    var millisBetweenRetries: Long = 60000
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): UntilSuccessfulConfig {
        val callSite = CallSiteCapture.capture()
        return UntilSuccessfulConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            maxRetries = maxRetries,
            millisBetweenRetries = millisBetweenRetries,
            pipeline = pipeline.toList(),
        )
    }
}
