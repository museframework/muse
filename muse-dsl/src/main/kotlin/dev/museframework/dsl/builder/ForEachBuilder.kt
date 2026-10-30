package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class ForEachBuilder : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var collection: String? = null
    var batchSize: Int = 1
    var batchSizeExpression: String? = null
    var rootMessageVariableName: String = "rootMessage"
    var counterVariableName: String = "counter"
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): ForEachConfig {
        val callSite = CallSiteCapture.capture()
        return ForEachConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            collection = collection,
            batchSize = batchSize,
            rootMessageVariableName = rootMessageVariableName,
            counterVariableName = counterVariableName,
            pipeline = pipeline.toList(),
            batchSizeExpression = batchSizeExpression,
        )
    }
}
