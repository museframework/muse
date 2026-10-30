package dev.museframework.dsl.builder
import dev.museframework.dsl.api.dsl.PipelineOwner

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router

@MuseDsl
class PipelineBuilder : PipelineOwner {
    private val elements = mutableListOf<PipelineElement>()

    fun add(element: PipelineElement) {
        elements.add(element)
    }

    override fun processor(processor: Processor) {
        elements.add(processor)
    }

    override fun router(router: Router) {
        elements.add(router)
    }

    fun build(): List<PipelineElement> = elements.toList()
}
