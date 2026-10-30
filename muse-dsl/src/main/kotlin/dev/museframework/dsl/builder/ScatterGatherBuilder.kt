package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.ScatterGatherRoute
import dev.museframework.dsl.api.config.TargetConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class ScatterGatherRouteBuilder : PipelineOwner {
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): ScatterGatherRoute = ScatterGatherRoute(
        pipeline = pipeline.toList(),
    )
}

@MuseDsl
class ScatterGatherBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var timeout: Long? = null
    var maxConcurrency: Int? = null
    var target: String? = null
    var targetValue: String? = null
    private val routes = mutableListOf<ScatterGatherRoute>()

    fun route(block: ScatterGatherRouteBuilder.() -> Unit) {
        routes.add(ScatterGatherRouteBuilder().apply(block).build())
    }

    fun build(): ScatterGatherConfig {
        val callSite = CallSiteCapture.capture()
        return ScatterGatherConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            routes = routes.toList(),
            timeout = timeout,
            maxConcurrency = maxConcurrency,
            targetConfig = target?.let { TargetConfig(target = it, targetValue = targetValue) },
        )
    }
}
