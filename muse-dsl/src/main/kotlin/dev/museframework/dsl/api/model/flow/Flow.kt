package dev.museframework.dsl.api.model.flow

import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Source
import dev.museframework.dsl.api.model.error.ErrorHandler

sealed class MuseFlow {
    abstract val name: String
    abstract val docId: String
    abstract val docDescription: String?
    abstract val pipeline: List<PipelineElement>

    data class Flow(
        override val name: String,
        override val docId: String,
        override val docDescription: String? = null,
        override val pipeline: List<PipelineElement> = emptyList(),
        val initialState: FlowInitialState = FlowInitialState.STARTED,
        val maxConcurrency: Int? = null,
        val source: Source? = null,
        val errorHandler: ErrorHandler? = null,
    ) : MuseFlow()

    data class SubFlow(
        override val name: String,
        override val docId: String,
        override val docDescription: String? = null,
        override val pipeline: List<PipelineElement> = emptyList(),
    ) : MuseFlow()
}

enum class FlowInitialState {
    STARTED,
    STOPPED,
}
