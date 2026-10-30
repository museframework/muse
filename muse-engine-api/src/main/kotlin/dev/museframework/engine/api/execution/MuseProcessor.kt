package dev.museframework.engine.api.execution

import dev.museframework.dsl.api.model.component.MuseComponent

interface MuseProcessor {
    val component: MuseComponent

    suspend fun process(context: ExecutionContext): ProcessorResult
}
