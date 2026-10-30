package dev.museframework.engine.api.execution

data class PipelineElement(
    val processor: MuseProcessor,
)

@JvmInline
value class Pipeline(
    val elements: List<PipelineElement>,
) {
    companion object {
        val empty = Pipeline(emptyList())
    }
}
