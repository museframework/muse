package dev.museframework.engine.api.execution.policy

import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.error.ErrorHandler
import dev.museframework.dsl.api.model.flow.FlowRef

/** Bootstrap-only declaration capabilities; every potential body must be declared. */
interface PolicyDefinition {
    fun inline(elements: List<PipelineElement>): BodyHandle

    fun flow(reference: FlowRef): BodyHandle

    /** Associates one local handler with the entire algorithm, rather than individual calls. */
    fun localHandler(handler: ErrorHandler)

    /**
     * Declares this binding's background work: one shared running limit and an optional deadline
     * measured from acceptance, including permit wait.
     */
    fun background(
        maxConcurrency: Int? = null,
        timeoutMillis: Long? = null,
    ): BackgroundHandle
}
