package dev.museframework.engine.components.processor.dsl

import dev.museframework.dsl.api.dsl.PipelineOwner

fun PipelineOwner.logger(
    block: LoggerBuilder.() -> Unit,
) {
    processor(LoggerBuilder().apply(block).build())
}