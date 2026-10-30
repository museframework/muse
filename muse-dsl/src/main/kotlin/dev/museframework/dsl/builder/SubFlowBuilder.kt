package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.flow.MuseFlow
import java.util.*

@MuseDsl
class SubFlowBuilder(private val name: String) : PipelineOwner {
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    private val pipeline = mutableListOf<PipelineElement>()

    override fun processor(processor: Processor) {
        pipeline.add(processor)
    }

    override fun router(router: Router) {
        pipeline.add(router)
    }

    fun build(): MuseFlow.SubFlow = MuseFlow.SubFlow(
        name = name,
        docId = docId,
        docDescription = docDescription,
        pipeline = pipeline.toList(),
    )
}

