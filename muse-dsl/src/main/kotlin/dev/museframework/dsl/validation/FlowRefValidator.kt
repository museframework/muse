package dev.museframework.dsl.validation

import dev.museframework.dsl.api.config.AsyncConfig
import dev.museframework.dsl.api.config.ChoiceConfig
import dev.museframework.dsl.api.config.ForEachConfig
import dev.museframework.dsl.api.config.ParallelForEachConfig
import dev.museframework.dsl.api.config.ScatterGatherConfig
import dev.museframework.dsl.api.config.TryConfig
import dev.museframework.dsl.api.config.UntilSuccessfulConfig
import dev.museframework.dsl.api.config.WithCorrelationIdConfig
import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.flow.FlowRef
import dev.museframework.dsl.api.model.flow.FlowRefConfig
import dev.museframework.dsl.api.model.flow.MuseFlow

fun validateFlowRefs(flows: Map<String, MuseFlow>): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()

    flows.values.forEach { flow ->
        val pipeline =
            when (flow) {
                is MuseFlow.Flow -> flow.pipeline
                is MuseFlow.SubFlow -> flow.pipeline
            }
        validatePipelineFlowRefs(pipeline, flows, errors)
    }

    return errors
}

private fun validatePipelineFlowRefs(
    pipeline: List<PipelineElement>,
    flows: Map<String, MuseFlow>,
    errors: MutableList<ValidationError>,
) {
    pipeline.forEach { component ->
        when (component) {
            is FlowRefConfig -> validateFlowRef(component, flows, errors)
            is UntilSuccessfulConfig -> validatePipelineFlowRefs(component.pipeline, flows, errors)
            is ForEachConfig -> validatePipelineFlowRefs(component.pipeline, flows, errors)
            is ParallelForEachConfig -> validatePipelineFlowRefs(component.pipeline, flows, errors)
            is ScatterGatherConfig ->
                component.routes.forEach { route ->
                    validatePipelineFlowRefs(route.pipeline, flows, errors)
                }
            is ChoiceConfig -> {
                component.whenRoutes.forEach { route ->
                    validatePipelineFlowRefs(route.pipeline, flows, errors)
                }
                component.otherwise?.let { otherwise ->
                    validatePipelineFlowRefs(otherwise, flows, errors)
                }
            }
            is WithCorrelationIdConfig -> validatePipelineFlowRefs(component.pipeline, flows, errors)
            is TryConfig -> {
                validatePipelineFlowRefs(component.pipeline, flows, errors)
                component.errorHandler?.strategies?.forEach { strategy ->
                    validatePipelineFlowRefs(strategy.pipeline, flows, errors)
                }
            }
            is AsyncConfig -> validatePipelineFlowRefs(component.pipeline, flows, errors)
        }
    }
}

private fun validateFlowRef(
    flowRef: FlowRefConfig,
    flows: Map<String, MuseFlow>,
    errors: MutableList<ValidationError>,
) {
    val byName = flowRef.ref as? FlowRef.ByName ?: return
    if (!flows.containsKey(byName.name)) {
        errors.add(
            ValidationError(
                message = "Flow reference '${byName.name}' not found in registered flows",
                component = flowRef,
            ),
        )
    }
}
