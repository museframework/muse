package dev.museframework.dsl.api.dsl

import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.dsl.api.model.component.Router

interface PipelineOwner {
    fun processor(processor: Processor)

    fun router(router: Router)
}
