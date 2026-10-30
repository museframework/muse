package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ChoiceRoute
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import java.util.*

@MuseDsl
class ChoiceWhenBuilder(private val expression: String) : PipelineOwner {
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): ChoiceRoute = ChoiceRoute(
        expression = expression,
        pipeline = pipeline.toList(),
    )
}

@MuseDsl
class ChoiceOtherwiseBuilder : PipelineOwner {
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): List<PipelineElement> = pipeline.toList()
}

@MuseDsl
class ChoiceBuilder {
    var docId: String = UUID.randomUUID().toString()
    var docName: String = ""
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    private val whenRoutes = mutableListOf<ChoiceRoute>()
    private var otherwisePipeline: List<PipelineElement>? = null

    fun `when`(expression: String, block: ChoiceWhenBuilder.() -> Unit) {
        whenRoutes.add(ChoiceWhenBuilder(expression).apply(block).build())
    }

    fun otherwise(block: ChoiceOtherwiseBuilder.() -> Unit) {
        otherwisePipeline = ChoiceOtherwiseBuilder().apply(block).build()
    }

    fun build(): ChoiceConfig {
        val callSite = CallSiteCapture.capture()
        return ChoiceConfig(
            docId = docId,
            docName = docName,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            whenRoutes = whenRoutes.toList(),
            otherwise = otherwisePipeline,
        )
    }
}
