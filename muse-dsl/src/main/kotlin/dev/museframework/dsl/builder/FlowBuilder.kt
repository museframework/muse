package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.dsl.api.model.flow.MuseFlow
import java.util.*

@MuseDsl
class FlowBuilder(private val name: String) : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var initialState: FlowInitialState = FlowInitialState.STARTED
    var maxConcurrency: Int? = null
    private var source: Source? = null
    private var errorHandler: ErrorHandler? = null
    private val pipeline = mutableListOf<PipelineElement>()

    fun source(source: Source) {
        this.source = source
    }

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun errorHandler(block: ErrorHandlerStrategyBuilder.() -> Unit) {
        errorHandler = ErrorHandlerStrategyBuilder().apply(block).build()
    }

    fun build(): MuseFlow.Flow = MuseFlow.Flow(
        name = name,
        docId = docId,
        docDescription = docDescription,
        initialState = initialState,
        maxConcurrency = maxConcurrency,
        source = source,
        pipeline = pipeline.toList(),
        errorHandler = errorHandler,
    )
}
