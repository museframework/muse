package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class WithCorrelationIdBuilder : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var correlationId: String = "#[correlationId]"
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): WithCorrelationIdConfig {
        val callSite = CallSiteCapture.capture()
        return WithCorrelationIdConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            correlationId = correlationId,
            pipeline = pipeline.toList(),
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
        )
    }
}

fun PipelineOwner.withCorrelationId(
    block: WithCorrelationIdBuilder.() -> Unit,
) {
    router(WithCorrelationIdBuilder().apply(block).build())
}
