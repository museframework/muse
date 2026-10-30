package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.model.component.PipelineElement
import dev.museframework.dsl.api.model.component.Router
import dev.museframework.dsl.api.model.error.ErrorHandler

typealias Pipeline = List<PipelineElement>

data class UntilSuccessfulConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val maxRetries: Int,
    val millisBetweenRetries: Long,
    val pipeline: Pipeline,
) : Router

data class ForEachConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val collection: String?,
    val batchSize: Int = 1,
    val rootMessageVariableName: String = "rootMessage",
    val counterVariableName: String = "counter",
    val pipeline: Pipeline,
    /** Static property-based size resolved during initialization, before iteration starts. */
    val batchSizeExpression: String? = null,
) : Router

data class ParallelForEachConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val collection: String?,
    val timeout: Long? = null,
    val maxConcurrency: Int? = null,
    val targetConfig: TargetConfig? = null,
    val pipeline: Pipeline,
) : Router

data class ScatterGatherRoute(
    val pipeline: List<PipelineElement>,
)

data class ScatterGatherConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val routes: List<ScatterGatherRoute>,
    val timeout: Long? = null,
    val maxConcurrency: Int? = null,
    val targetConfig: TargetConfig? = null,
) : Router

data class ChoiceRoute(
    val expression: String,
    val pipeline: List<PipelineElement>,
)

data class ChoiceConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val whenRoutes: List<ChoiceRoute>,
    val otherwise: List<PipelineElement>?,
) : Router

data class WithCorrelationIdConfig(
    override val docName: String = "",
    override val docId: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val correlationId: String,
    val pipeline: Pipeline = emptyList(),
) : Router

data class TryConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val pipeline: Pipeline,
    val errorHandler: ErrorHandler?,
    val transactionalAction: String? = null,
) : Router

data class AsyncConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val pipeline: Pipeline,
    val maxConcurrency: Int? = null,
) : Router
