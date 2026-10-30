package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorHandler
import java.util.*

@MuseDsl
class TryBuilder : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var transactionalAction: String? = null
    private val pipeline = mutableListOf<PipelineElement>()
    private var errorHandler: ErrorHandler? = null

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun errorHandler(block: ErrorHandlerStrategyBuilder.() -> Unit) {
        errorHandler = ErrorHandlerStrategyBuilder().apply(block).build()
    }

    fun build(): TryConfig {
        val callSite = CallSiteCapture.capture()
        return TryConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            pipeline = pipeline.toList(),
            errorHandler = errorHandler,
            transactionalAction = transactionalAction,
        )
    }
}
