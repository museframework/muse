package dev.museframework.engine.integration

import dev.museframework.dsl.api.model.component.Processor
import dev.museframework.engine.api.execution.ExecutionContext
import dev.museframework.engine.api.execution.MuseProcessor
import dev.museframework.engine.api.execution.ProcessorResult

data class CaptureConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
) : Processor

class CaptureMuseProcessor<T : Processor>(
    override val component: T,
    private val onProcess: suspend (ExecutionContext) -> ProcessorResult,
) : MuseProcessor {
    override suspend fun process(context: ExecutionContext): ProcessorResult = onProcess(context)
}
