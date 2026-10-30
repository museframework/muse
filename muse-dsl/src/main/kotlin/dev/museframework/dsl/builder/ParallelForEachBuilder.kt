package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.Pipeline
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class ParallelForEachBuilder : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var collection: String? = null
    var timeout: Long? = null
    var maxConcurrency: Int? = null
    var target: String? = null
    var targetValue: String? = null
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): ParallelForEachConfig {
        timeout?.let { configuredTimeout ->
            require(configuredTimeout > 0) {
                "Parallel For Each timeout must be greater than 0 when configured, but was $configuredTimeout"
            }
        }
        maxConcurrency?.let { configuredMaxConcurrency ->
            require(configuredMaxConcurrency > 0) {
                "Parallel For Each maxConcurrency must be greater than 0 when configured, but was $configuredMaxConcurrency"
            }
        }

        val callSite = CallSiteCapture.capture()
        return ParallelForEachConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            collection = collection,
            timeout = timeout,
            maxConcurrency = maxConcurrency,
            targetConfig = target?.let { TargetConfig(target = it, targetValue = targetValue) },
            pipeline = pipeline.toList(),
        )
    }
}
